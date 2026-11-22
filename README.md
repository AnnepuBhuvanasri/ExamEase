# ExamEase – Online Examination System

A Java + MySQL DBMS project with HTML/CSS/JavaScript frontend.

## Requirements
- JDK 17 or later
- MySQL Server 8.x and MySQL Workbench
- Apache Maven 3.8+
- Internet connection for Maven to download dependencies on the first build

## 1. Create the database
1. Open MySQL Workbench and connect to your local MySQL server.
2. Open `database.sql`.
3. Execute the complete script.
4. It creates the `examease` database and its tables.

## 2. Configure the MySQL password
Open `src/main/java/com/examease/Main.java`.
Find:
`static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "YOUR_MYSQL_PASSWORD");`
Replace `YOUR_MYSQL_PASSWORD` with your MySQL root password.
Do not include quotation marks inside the password itself. If your MySQL root account has no password, use an empty string: `""`.

The default database connection is:
`jdbc:mysql://localhost:3306/examease?useSSL=false&serverTimezone=UTC`
If your MySQL uses another port, change `3306`.

## 3. Run the project
Open Command Prompt in the `ExamEase` folder (the folder containing `pom.xml`).

Run:
`mvn clean compile`
Then:
`mvn exec:java`

Wait until the terminal displays:
`ExamEase running at http://localhost:8080`

Open this address in Chrome.

## 4. Create an admin account
1. Open the website and register a normal student account.
2. In MySQL Workbench, run the following, replacing the email with the one you registered:
   `USE examease;`
   `UPDATE users SET role='ADMIN' WHERE email='your-email@example.com';`
3. Log out of the website and log in again with that email.
4. You should now see the Admin dashboard.

Do not create an admin by editing frontend JavaScript. The role is checked by the Java backend.

## 5. Test the project
1. Register a second account as a student.
2. Log in as admin and create an exam. Add at least one question, four options, and select the correct option.
3. Log out and log in as the student.
4. Start the exam, select answers and submit.
5. Check the student's result history.
6. Log in as admin to retrieve all submitted results.

## Main API routes
- `POST /api/register`
- `POST /api/login`
- `POST /api/logout`
- `GET /api/me`
- `GET /api/exams`
- `GET /api/exams/{id}`
- `POST /api/submit`
- `GET /api/results`
- `POST /api/admin/exams`
- `GET /api/admin/students`
- `GET /api/admin/results`

## Database tables
`users`, `exams`, `questions`, `options`, `attempts`, `responses`

## Important notes
- Passwords are stored as salted PBKDF2 hashes, not plain text.
- SQL statements use JDBC prepared statements.
- Exam answers are evaluated on the Java server. Correct answers are not sent to the student browser.
- Submission and response inserts are performed in a database transaction.
- This is an educational local project, not a production exam platform. Before public deployment, add HTTPS, stronger session management, CSRF protection, exam attempt controls, audit logging and rate limiting.
- This starter version does not yet prevent a student from taking the same exam more than once, and the timer is enforced by the browser rather than a server-side deadline. These are good extensions for your project demonstration.
