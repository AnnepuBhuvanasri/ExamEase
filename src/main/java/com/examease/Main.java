package com.examease;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.SecretKeyFactory;

public class Main {
    static final Gson G = new Gson();
    static final Map<String, UserSession> SESSIONS = new ConcurrentHashMap<>();
    static final String DB_URL = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/examease?useSSL=false&serverTimezone=UTC");
    static final String DB_USER = System.getenv().getOrDefault("DB_USER", "root");
    static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "YOUR_MYSQL_PASSWORD");
    record UserSession(int id, String name, String email, String role) {}

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection ignored = db()) { System.out.println("Connected to MySQL database examease."); }
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api", Main::api);
        server.createContext("/", Main::staticFiles);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("ExamEase running at http://localhost:8080");
    }

    static Connection db() throws SQLException { return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS); }
    static JsonObject obj(String k, String v) { JsonObject o=new JsonObject(); o.addProperty(k,v); return o; }
    static void send(HttpExchange x, int status, JsonElement data) throws IOException {
        byte[] b=G.toJson(data).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");
        x.sendResponseHeaders(status,b.length); x.getResponseBody().write(b); x.close();
    }
    static void error(HttpExchange x,int status,String message) throws IOException { send(x,status,obj("error",message)); }
    static JsonObject body(HttpExchange x) throws IOException {
        return JsonParser.parseString(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
    }
    static String cookie(HttpExchange x,String name) {
        String c=x.getRequestHeaders().getFirst("Cookie"); if(c==null)return null;
        for(String part:c.split(";")) { String[] p=part.trim().split("=",2); if(p.length==2&&p[0].equals(name))return p[1]; }
        return null;
    }
    static UserSession current(HttpExchange x) { String sid=cookie(x,"EXAMEASE"); return sid==null?null:SESSIONS.get(sid); }
    static boolean require(HttpExchange x, String role) throws IOException {
        UserSession u=current(x);
        if(u==null){error(x,401,"Please log in first.");return false;}
        if(role!=null&&!role.equals(u.role())){error(x,403,"You do not have permission for this action.");return false;}
        return true;
    }
    static void api(HttpExchange x) throws IOException {
        try {
            String method=x.getRequestMethod(), path=x.getRequestURI().getPath();
            if(path.equals("/api/register")&&method.equals("POST")){register(x);return;}
            if(path.equals("/api/login")&&method.equals("POST")){login(x);return;}
            if(path.equals("/api/logout")&&method.equals("POST")){String sid=cookie(x,"EXAMEASE");if(sid!=null)SESSIONS.remove(sid);x.getResponseHeaders().add("Set-Cookie","EXAMEASE=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");send(x,200,obj("message","Logged out"));return;}
            if(path.equals("/api/me")&&method.equals("GET")){UserSession u=current(x);if(u==null){error(x,401,"Not logged in");return;}JsonObject o=new JsonObject();o.addProperty("id",u.id());o.addProperty("name",u.name());o.addProperty("email",u.email());o.addProperty("role",u.role());send(x,200,o);return;}
            if(path.equals("/api/exams")&&method.equals("GET")){if(!require(x,null))return;listExams(x);return;}
            if(path.startsWith("/api/exams/")&&method.equals("GET")){if(!require(x,null))return;getExam(x,Integer.parseInt(path.substring("/api/exams/".length())));return;}
            if(path.equals("/api/submit")&&method.equals("POST")){if(!require(x,"STUDENT"))return;submit(x);return;}
            if(path.equals("/api/results")&&method.equals("GET")){if(!require(x,"STUDENT"))return;studentResults(x);return;}
            if(path.equals("/api/admin/exams")&&method.equals("POST")){if(!require(x,"ADMIN"))return;createExam(x);return;}
            if(path.equals("/api/admin/results")&&method.equals("GET")){if(!require(x,"ADMIN"))return;adminResults(x);return;}
            if(path.equals("/api/admin/students")&&method.equals("GET")){if(!require(x,"ADMIN"))return;students(x);return;}
            error(x,404,"API route not found.");
        } catch (IllegalArgumentException e) { error(x,400,e.getMessage()==null?"Invalid request":e.getMessage()); }
          catch (Exception e) { e.printStackTrace(); error(x,500,"Server error. Check the terminal for details."); }
    }
    static void register(HttpExchange x)throws Exception{
        JsonObject b=body(x);String name=req(b,"name"),email=req(b,"email").toLowerCase(),pass=req(b,"password");
        if(pass.length()<8)throw new IllegalArgumentException("Password must contain at least 8 characters.");
        try(Connection c=db();PreparedStatement p=c.prepareStatement("INSERT INTO users(full_name,email,password_hash,role) VALUES(?,?,?,'STUDENT')",Statement.RETURN_GENERATED_KEYS)){
            p.setString(1,name);p.setString(2,email);p.setString(3,hash(pass));p.executeUpdate();int id;
            try(ResultSet r=p.getGeneratedKeys()){r.next();id=r.getInt(1);}
            establish(x,new UserSession(id,name,email,"STUDENT"));
        }catch(SQLIntegrityConstraintViolationException e){throw new IllegalArgumentException("An account with this email already exists.");}
    }
    static void login(HttpExchange x)throws Exception{
        JsonObject b=body(x);String email=req(b,"email").toLowerCase(),pass=req(b,"password");
        try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT user_id,full_name,email,password_hash,role FROM users WHERE email=?")){
            p.setString(1,email);try(ResultSet r=p.executeQuery()){
                if(!r.next()||!verify(pass,r.getString("password_hash"))){error(x,401,"Incorrect email or password.");return;}
                establish(x,new UserSession(r.getInt("user_id"),r.getString("full_name"),r.getString("email"),r.getString("role")));
            }
        }
    }
    static void establish(HttpExchange x,UserSession u)throws IOException{
        String sid=UUID.randomUUID().toString();SESSIONS.put(sid,u);
        x.getResponseHeaders().add("Set-Cookie","EXAMEASE="+sid+"; Path=/; HttpOnly; SameSite=Lax");
        JsonObject o=new JsonObject();o.addProperty("id",u.id());o.addProperty("name",u.name());o.addProperty("email",u.email());o.addProperty("role",u.role());send(x,200,o);
    }
    static void listExams(HttpExchange x)throws Exception{
        JsonArray a=new JsonArray();try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT e.exam_id,e.title,e.description,e.duration_minutes,(SELECT COUNT(*) FROM questions q WHERE q.exam_id=e.exam_id) question_count FROM exams e WHERE e.is_active=TRUE ORDER BY e.created_at DESC");ResultSet r=p.executeQuery()){
            while(r.next()){JsonObject o=new JsonObject();o.addProperty("id",r.getInt(1));o.addProperty("title",r.getString(2));o.addProperty("description",r.getString(3));o.addProperty("duration",r.getInt(4));o.addProperty("questions",r.getInt(5));a.add(o);}
        }send(x,200,a);
    }
    static void getExam(HttpExchange x,int id)throws Exception{
        JsonObject out=new JsonObject();JsonArray qs=new JsonArray();
        try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT exam_id,title,description,duration_minutes FROM exams WHERE exam_id=? AND is_active=TRUE")){
            p.setInt(1,id);try(ResultSet r=p.executeQuery()){if(!r.next()){error(x,404,"Exam not found.");return;}out.addProperty("id",id);out.addProperty("title",r.getString(2));out.addProperty("description",r.getString(3));out.addProperty("duration",r.getInt(4));}
        }
        try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT q.question_id,q.question_text,o.option_id,o.option_text FROM questions q JOIN options o ON q.question_id=o.question_id WHERE q.exam_id=? ORDER BY q.question_id,o.option_id")){
            p.setInt(1,id);try(ResultSet r=p.executeQuery()){JsonObject q=null;int last=-1;while(r.next()){int qid=r.getInt(1);if(qid!=last){q=new JsonObject();q.addProperty("id",qid);q.addProperty("text",r.getString(2));q.add("options",new JsonArray());qs.add(q);last=qid;}JsonObject op=new JsonObject();op.addProperty("id",r.getInt(3));op.addProperty("text",r.getString(4));q.getAsJsonArray("options").add(op);}}
        }
        out.add("questions",qs);send(x,200,out);
    }
    static void submit(HttpExchange x)throws Exception{
        UserSession u=current(x);JsonObject b=body(x);int examId=b.get("examId").getAsInt();JsonObject answers=b.getAsJsonObject("answers");
        try(Connection c=db()){
            c.setAutoCommit(false);
            try{
                int total=0,score=0;List<int[]> rows=new ArrayList<>();
                try(PreparedStatement p=c.prepareStatement("SELECT q.question_id,o.option_id,o.is_correct FROM questions q JOIN options o ON q.question_id=o.question_id WHERE q.exam_id=?")){
                    p.setInt(1,examId);try(ResultSet r=p.executeQuery()){Map<Integer,Integer> correct=new HashMap<>();while(r.next()){if(r.getBoolean(3))correct.put(r.getInt(1),r.getInt(2));}
                        total=correct.size();
                        for(var e:correct.entrySet()){int selected=answers!=null&&answers.has(String.valueOf(e.getKey()))?answers.get(String.valueOf(e.getKey())).getAsInt():-1;boolean ok=selected==e.getValue();if(ok)score++;rows.add(new int[]{e.getKey(),selected,ok?1:0});}}
                }
                if(total==0)throw new IllegalArgumentException("This exam has no questions yet.");
                try(PreparedStatement p=c.prepareStatement("INSERT INTO attempts(user_id,exam_id,score,total_questions,percentage) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                    p.setInt(1,u.id());p.setInt(2,examId);p.setInt(3,score);p.setInt(4,total);p.setDouble(5,score*100.0/total);p.executeUpdate();int aid;try(ResultSet r=p.getGeneratedKeys()){r.next();aid=r.getInt(1);}
                    try(PreparedStatement rp=c.prepareStatement("INSERT INTO responses(attempt_id,question_id,selected_option_id,is_correct) VALUES(?,?,?,?)")){
                        for(int[] row:rows){rp.setInt(1,aid);rp.setInt(2,row[0]);if(row[1]<0)rp.setNull(3,Types.INTEGER);else rp.setInt(3,row[1]);rp.setBoolean(4,row[2]==1);rp.addBatch();}rp.executeBatch();
                    }
                    c.commit();JsonObject out=new JsonObject();out.addProperty("score",score);out.addProperty("total",total);out.addProperty("percentage",Math.round(score*10000.0/total)/100.0);send(x,200,out);
                }
            }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
        }
    }
    static void studentResults(HttpExchange x)throws Exception{
        UserSession u=current(x);JsonArray a=new JsonArray();String sql="SELECT a.attempt_id,e.title,a.score,a.total_questions,a.percentage,a.submitted_at FROM attempts a JOIN exams e ON a.exam_id=e.exam_id WHERE a.user_id=? ORDER BY a.submitted_at DESC";
        try(Connection c=db();PreparedStatement p=c.prepareStatement(sql)){p.setInt(1,u.id());try(ResultSet r=p.executeQuery()){while(r.next()){JsonObject o=new JsonObject();o.addProperty("attemptId",r.getInt(1));o.addProperty("exam",r.getString(2));o.addProperty("score",r.getInt(3));o.addProperty("total",r.getInt(4));o.addProperty("percentage",r.getDouble(5));o.addProperty("date",r.getString(6));a.add(o);}}}send(x,200,a);
    }
    static void createExam(HttpExchange x)throws Exception{
        JsonObject b=body(x);String title=req(b,"title");int duration=b.get("duration").getAsInt();if(duration<1||duration>300)throw new IllegalArgumentException("Duration must be between 1 and 300 minutes.");
        JsonArray questions=b.getAsJsonArray("questions");if(questions==null||questions.size()==0)throw new IllegalArgumentException("Add at least one question.");
        try(Connection c=db()){c.setAutoCommit(false);try{
            int eid;try(PreparedStatement p=c.prepareStatement("INSERT INTO exams(title,description,duration_minutes) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)){p.setString(1,title);p.setString(2,b.has("description")?b.get("description").getAsString():"");p.setInt(3,duration);p.executeUpdate();try(ResultSet r=p.getGeneratedKeys()){r.next();eid=r.getInt(1);}}
            for(JsonElement qe:questions){JsonObject q=qe.getAsJsonObject();String qt=req(q,"text");JsonArray opts=q.getAsJsonArray("options");int correct=q.get("correct").getAsInt();if(opts==null||opts.size()!=4||correct<0||correct>3)throw new IllegalArgumentException("Each question needs exactly four options and one correct answer.");int qid;
                try(PreparedStatement p=c.prepareStatement("INSERT INTO questions(exam_id,question_text) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)){p.setInt(1,eid);p.setString(2,qt);p.executeUpdate();try(ResultSet r=p.getGeneratedKeys()){r.next();qid=r.getInt(1);}}
                try(PreparedStatement p=c.prepareStatement("INSERT INTO options(question_id,option_text,is_correct) VALUES(?,?,?)")){for(int i=0;i<4;i++){p.setInt(1,qid);p.setString(2,opts.get(i).getAsString());p.setBoolean(3,i==correct);p.addBatch();}p.executeBatch();}
            }c.commit();send(x,201,obj("message","Exam created successfully."));
        }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}}
    }
    static void adminResults(HttpExchange x)throws Exception{
        JsonArray a=new JsonArray();String sql="SELECT a.attempt_id,u.full_name,u.email,e.title,a.score,a.total_questions,a.percentage,a.submitted_at FROM attempts a JOIN users u ON a.user_id=u.user_id JOIN exams e ON a.exam_id=e.exam_id ORDER BY a.submitted_at DESC";
        try(Connection c=db();PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){while(r.next()){JsonObject o=new JsonObject();o.addProperty("attemptId",r.getInt(1));o.addProperty("student",r.getString(2));o.addProperty("email",r.getString(3));o.addProperty("exam",r.getString(4));o.addProperty("score",r.getInt(5));o.addProperty("total",r.getInt(6));o.addProperty("percentage",r.getDouble(7));o.addProperty("date",r.getString(8));a.add(o);}}send(x,200,a);
    }
    static void students(HttpExchange x)throws Exception{
        JsonArray a=new JsonArray();try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT user_id,full_name,email,created_at FROM users WHERE role='STUDENT' ORDER BY full_name");ResultSet r=p.executeQuery()){while(r.next()){JsonObject o=new JsonObject();o.addProperty("id",r.getInt(1));o.addProperty("name",r.getString(2));o.addProperty("email",r.getString(3));o.addProperty("joined",r.getString(4));a.add(o);}}send(x,200,a);
    }
    static String req(JsonObject o,String key){if(o==null||!o.has(key)||o.get(key).isJsonNull())throw new IllegalArgumentException("Missing field: "+key);String v=o.get(key).getAsString().trim();if(v.isEmpty())throw new IllegalArgumentException(key+" cannot be empty.");return v;}
    static String hash(String password)throws Exception{byte[] salt=new byte[16];SecureRandom.getInstanceStrong().nextBytes(salt);PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,120000,256);byte[] h=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(h);}
    static boolean verify(String password,String stored)throws Exception{String[] p=stored.split(":");if(p.length!=2)return false;byte[] salt=Base64.getDecoder().decode(p[0]),expected=Base64.getDecoder().decode(p[1]);PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,120000,256);byte[] actual=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return MessageDigest.isEqual(expected,actual);}
    static void staticFiles(HttpExchange x)throws IOException{
        String path=x.getRequestURI().getPath();if(path.equals("/"))path="/index.html";
        if(path.contains("..")){x.sendResponseHeaders(400,-1);return;}
        File f=new File("web",path.substring(1));if(!f.exists()||f.isDirectory()){x.sendResponseHeaders(404,-1);return;}
        String type=path.endsWith(".css")?"text/css":path.endsWith(".js")?"application/javascript":"text/html";
        byte[] data=java.nio.file.Files.readAllBytes(f.toPath());x.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);x.close();
    }
}
