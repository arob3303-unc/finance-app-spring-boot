# 00 — How a Request Actually Works

> **Read this one slowly.** Everything else in this guide is a variation on the mechanism
> described here. Once you can narrate the path of a single HTTP request out loud, the rest
> of Spring Boot stops being magic and becomes just code you haven't read yet.

We are going to trace **your own code** — the `SoftwareEngineer` endpoints you already
wrote — rather than a new example. You built a working vertical slice. Let's find out what
it actually does.

---

## 1. The shape of the whole thing

A Spring Boot web application is four layers with one job each. Draw this on paper once:

```
       HTTP request  (text over a TCP socket)
            │
┌───────────▼──────────────────────────────────────────────┐
│ Tomcat          "I speak HTTP."                          │
│                 Parses bytes into an HttpServletRequest. │
├──────────────────────────────────────────────────────────┤
│ DispatcherServlet                                        │
│                 "Which of your methods handles this?"    │
│                 Matches URL + verb to a controller.      │
├──────────────────────────────────────────────────────────┤
│ @RestController  "I translate HTTP <-> Java."            │
│   Controller     Knows about status codes and JSON.      │
│                  Knows NOTHING about business rules.     │
├──────────────────────────────────────────────────────────┤
│ @Service         "I know the business rules."             │
│   Service        Knows nothing about HTTP.                │
│                  This is where 'you cannot overdraw'     │
│                  will live.                               │
├──────────────────────────────────────────────────────────┤
│ @Repository      "I know how to talk to the database."   │
│   Repository     You write an interface; Spring writes   │
│   (JpaRepository) the implementation.                    │
├──────────────────────────────────────────────────────────┤
│ Hibernate / JPA  "I turn objects into SQL."              │
├──────────────────────────────────────────────────────────┤
│ JDBC driver      "I speak the Postgres wire protocol."   │
└───────────┬──────────────────────────────────────────────┘
            │
       Postgres (in Docker, port 5332)
```

**Why bother with so many layers?** One reason: each layer has exactly one reason to
change. When you later add an Angular frontend, a mobile app, and a nightly batch job, all
three need the rule *"you cannot transfer more money than you have."* If that rule lives in
the controller, it lives next to HTTP concerns and only the web API gets it. In the service,
all three share it.

The honest test of a layer boundary: **can you describe the service layer without using the
words "HTTP", "JSON", or "status code"?** If yes, the boundary is intact.

---

## 2. Tracing `GET /api/v1/software-engineers`

Here is the request. Run it yourself:

```bash
curl -i http://localhost:8081/api/v1/software-engineers
```

### Hop 1 — Tomcat accepts the socket

Spring Boot embeds Tomcat; it is a library inside your app, not a server you deploy into.
You saw it claim the port at startup:

```
o.s.boot.tomcat.TomcatWebServer : Tomcat started on port 8081 (http) with context path '/'
```

Tomcat reads the raw bytes and builds an `HttpServletRequest` object: method `GET`, path
`/api/v1/software-engineers`, headers, empty body.

### Hop 2 — DispatcherServlet routes it

One servlet receives every request and dispatches it. Note *when* it initialized in your
log — not at startup, but on the **first request**:

```
[nio-8081-exec-1] o.s.web.servlet.DispatcherServlet : Initializing Servlet 'dispatcherServlet'
[nio-8081-exec-1] o.s.web.servlet.DispatcherServlet : Completed initialization in 5 ms
```

That `nio-8081-exec-1` is the worker thread handling your request. Tomcat keeps a pool of
them; each concurrent request gets its own. **Remember this thread pool** — in Milestone 6
it is exactly why two simultaneous transfers can corrupt a balance.

At startup Spring built a lookup table from your annotations:

| Verb | Path pattern | Handler method |
|---|---|---|
| GET | `/api/v1/software-engineers` | `SoftwareEngineerController.getEngineers()` |
| POST | `/api/v1/software-engineers` | `SoftwareEngineerController.addNewSoftwareEngineer(..)` |

The `DispatcherServlet` looks up `GET` + that path and finds your method.

### Hop 3 — Your controller

`src/main/java/com/austin/finance_app_idea/SoftwareEngineerController.java`:

```java
@GetMapping
public List<SoftwareEngineer> getEngineers() {
    return softwareEngineerService.getAllSoftwareEngineers();
}
```

Look at how little it does. It takes no parameters, makes no decisions, and does not mention
JSON. That is the point — **a controller is a translator, not a brain.** It delegates
immediately.

### Hop 4 — Your service

```java
public List<SoftwareEngineer> getAllSoftwareEngineers() {
    return softwareEngineerRepository.findAll();
}
```

Right now this is a pass-through, and it would be fair to ask why it exists at all. The
answer is "it doesn't earn its keep *yet*." In Milestone 3 the equivalent method becomes
~30 lines of rules: both accounts exist, same currency, both open, sufficient funds, write
two ledger rows, all-or-nothing. That is what a service is for. We keep the layer now so
the shape is already right when the rules arrive.

### Hop 5 — The repository you never implemented

This is the part that deserves a long stare:

```java
public interface SoftwareEngineerRepository
        extends JpaRepository<SoftwareEngineer, Integer> {
}
```

An **interface**, with **no methods** and **no implementing class anywhere in your project**
— yet `findAll()` and `save()` work. At startup Spring Data JPA finds every interface
extending `JpaRepository`, generates a proxy class implementing it at runtime, and registers
that proxy as a bean. `JpaRepository` already declares `findAll`, `findById`, `save`,
`deleteById`, `count`, and pagination methods, so you inherit a working CRUD layer from the
two type parameters alone:

- `SoftwareEngineer` — the entity it manages
- `Integer` — the type of that entity's `@Id`

Later you'll add method *names* like `findByAccountId(UUID id)` and Spring Data will parse
the name and write the query for you.

### Hop 6 — Hibernate generates SQL

The repository asks Hibernate for all `SoftwareEngineer` instances. Hibernate consults the
entity mapping and emits SQL. Because `spring.jpa.show-sql=true`, you can read it:

```
Hibernate:
    select
        se1_0.id,
        se1_0.name,
        se1_0.tech_stack
    from
        software_engineer se1_0
```

**Two things to notice, and they matter:**

1. You never wrote that SQL, and you never wrote the table or column names. The class
   `SoftwareEngineer` mapped to table `software_engineer`; the field `techStack` mapped to
   column `tech_stack`. Hibernate's default naming strategy converts `CamelCase` to
   `snake_case`. This is why the migration file in `db/migration` spells the column
   `tech_stack` — spell it `techStack` instead and the app refuses to boot.
2. `select id, name, tech_stack` — **not** `select *`. Hibernate asks for precisely the
   columns mapped by the entity.

Keep `show-sql` on for this entire project. The gap between "the Java I wrote" and "the SQL
that ran" is where almost every performance bug and surprise lives.

### Hop 7 — Rows become objects

The JDBC driver returns a `ResultSet` — a grid of values. Hibernate instantiates a
`SoftwareEngineer` per row and populates the fields. This is the **O/RM** boundary:
*object/relational mapping*, tables on one side, objects on the other.

### Hop 8 — Objects become JSON

Your method returns `List<SoftwareEngineer>`. Nothing in your code converts that to JSON.
`@RestController` does: it tells Spring that return values are the **response body**, so
Spring hands the object to **Jackson**, which reflects over the getters (yours come from
Lombok's `@Getter`) and writes:

```json
[{"id":1,"name":"Austin","techStack":"Java, Spring Boot, Angular"}]
```

Note `techStack` is camelCase here while the database column is `tech_stack`. Two different
naming conventions, two different boundaries, one Java field in the middle.

Then Tomcat writes the status line, headers, and that body back down the socket.

**The whole round trip, one line:**

> socket → Tomcat → DispatcherServlet → controller → service → repository proxy →
> Hibernate → SQL → Postgres → ResultSet → entities → Jackson → JSON → socket

---

## 3. The same path backwards: `POST`

A POST reverses the two translation steps. Run it:

```bash
curl -i -X POST http://localhost:8081/api/v1/software-engineers \
  -H "Content-Type: application/json" \
  -d '{"name":"Austin","techStack":"Java, Spring Boot, Angular"}'
```

```java
@PostMapping
public void addNewSoftwareEngineer(@RequestBody SoftwareEngineer softwareEngineer) {
    softwareEngineerService.insertSoftwareEngineer(softwareEngineer);
}
```

`@RequestBody` is the instruction *"take the bytes in the request body, hand them to
Jackson, and give me the resulting object."* Jackson matches JSON keys to fields using the
setters (Lombok's `@Setter`) and needs the **no-argument constructor** you wrote — that is
why `public SoftwareEngineer() {}` exists even though nothing in your code calls it.

The `Content-Type: application/json` header is not decoration. Drop it and you get **415
Unsupported Media Type**: Spring picks the deserializer by content type, and without the
header it doesn't know you sent JSON. Try it once — a header you broke on purpose teaches
more than one that always worked.

Then `save()` emits an `insert`, and Postgres assigns the `id`.

### Two things already wrong with this POST (we fix them in Milestone 2)

1. **It returns `200 OK`, but creating a resource should return `201 Created`** with a
   `Location` header pointing at the new resource. It returns 200 because the method returns
   `void`, so Spring defaults to 200 with an empty body. The client has no idea what ID it
   just created.
2. **It accepts the entity directly as the request body.** So a client can POST
   `{"id": 9999, "name": "..."}` and set the primary key. On an `Account`, the equivalent
   lets a caller set fields they must never control. The fix is a separate **DTO** — a type
   that describes *what a client may send* — which is exactly why Milestone 2 spends real
   time on DTOs.

Notice that neither of these is a crash. Both "work." That is what makes them worth
pointing out.

---

## 4. The annotations, decoded

You wrote all of these. Here is what each one actually asks for.

| Annotation | Plain English |
|---|---|
| `@SpringBootApplication` | Start here. Scan this package and below for things to wire up, and auto-configure based on what's on the classpath. |
| `@RestController` | This class handles web requests, and return values are the **response body** (not a view/template name). |
| `@RequestMapping("api/v1/...")` | Prefix every route in this class with this path. |
| `@GetMapping` / `@PostMapping` | Handle GET / POST at the class's path. |
| `@RequestBody` | Deserialize the request body into this parameter. |
| `@Service` | A bean holding business logic. Functionally like `@Component`; the distinct name documents intent. |
| `@Entity` | This class maps to a database table. |
| `@Id` | This field is the primary key. |
| `@GeneratedValue(strategy = IDENTITY)` | The **database** generates the key on insert; read it back afterwards. |
| `@Getter` / `@Setter` (Lombok) | Generate getters/setters at compile time. Jackson and Hibernate both need them. |

### Why there is no `@Autowired` anywhere

```java
private final SoftwareEngineerService softwareEngineerService;

public SoftwareEngineerController(SoftwareEngineerService softwareEngineerService) {
    this.softwareEngineerService = softwareEngineerService;
}
```

You never call `new SoftwareEngineerController(...)`. Spring does, at startup, and it has to
supply that argument — so it looks in its **application context** (its registry of beans)
for a `SoftwareEngineerService`, finds the one `@Service` created, and passes it in. That is
**dependency injection**: a class declares what it needs and is handed it, rather than
constructing it.

Since Spring 4.3, a class with a **single constructor** needs no `@Autowired` — Spring uses
it automatically. Constructor injection (rather than `@Autowired` on a field) is the right
default because it lets the field be `final`, makes the dependency impossible to forget, and
lets you build the class in a unit test with plain `new` and a mock. You'll do exactly that
in Milestone 8.

Worth internalizing now, because it will pay off in Milestone 4: **Angular has the same
concept, under the same name.** An Angular component declares `private api = inject(AccountApi)`
and the framework supplies it. Same idea, different language.

---

## 5. Who owns the database schema?

This is the one real behavioural change in Milestone 0, and it fixes a bug you may not have
noticed yet.

### What was happening

`application.properties` had:

```properties
spring.jpa.hibernate.ddl-auto=create-drop
```

That means: **on startup, Hibernate CREATEs the tables from your entity classes; on
shutdown, it DROPs them.** When we inspected your `finance` database at the start of this
milestone, here is what was in it:

```
$ docker exec postgres-spring-boot psql -U finance-app-idea -d finance -c "\dt"
Did not find any tables.
```

Empty. Your last clean shutdown destroyed `software_engineer` and every row in it. That is
`create-drop` working exactly as documented — and it is also why you may have felt like data
"didn't stick." It didn't.

`create-drop` is genuinely useful for throwaway experiments. It is unusable for an
application that is supposed to remember anything, and it is unthinkable for a bank.

### What happens now

Schema ownership moves to **Flyway**. Plain `.sql` files in
`src/main/resources/db/migration` are applied in version order before JPA starts:

```
V1__create_software_engineer.sql
```

Flyway tracks what it has run in a table it creates itself:

```
 installed_rank | version |       description        | type | success
----------------+---------+--------------------------+------+---------
              1 | 1       | create software engineer | SQL  | t
```

And Hibernate's role shrinks to `validate`:

```properties
spring.jpa.hibernate.ddl-auto=validate
```

Now Hibernate *compares* the entity classes against the real tables at startup and refuses
to boot if they disagree. We saw it do its job during this milestone — before the migration
was wired up correctly, startup failed with:

```
SchemaManagementException: Schema validation: missing table [software_engineer]
```

That failure is the **feature**. The alternative is an app that starts happily and throws
`column does not exist` at 2am on the one code path nobody tested.

### The rule that makes migrations work

**Once a migration has run anywhere but your own machine, you never edit it.** Flyway stores
a checksum of each applied file and refuses to start if one changed. To alter a table you
add `V2`, `V3`, … You never rewrite history. That single constraint is what makes it safe to
evolve a production database, and it's why in Milestone 2 we *add* a migration to drop
`software_engineer` rather than deleting `V1`.

**Try this to feel it:** open `V1__create_software_engineer.sql`, change a word in a
comment, restart. Flyway stops the app with a checksum mismatch. Change it back.

---

## 6. Spring Boot 4 trap: a library on the classpath is not a feature

Worth its own section, because it cost us a debugging cycle in this very milestone and it
will bite you again.

The first attempt added the obvious dependencies:

```xml
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
```

The app failed with `missing table [software_engineer]`, and the log contained **zero lines
mentioning Flyway**. Flyway was on the classpath and simply never ran.

The reason: in Spring Boot 3, auto-configuration for everything lived in one big
`spring-boot-autoconfigure` jar. **Boot 4 split it into per-integration modules.** The
Flyway *engine* comes from `org.flywaydb`, but the *Boot integration that runs it for you*
is a separate artifact:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-flyway</artifactId>
</dependency>
```

You have already met this split without noticing: your `pom.xml` says
`spring-boot-starter-webmvc`, not the `spring-boot-starter-web` every tutorial shows. Same
reorganization.

**The transferable lesson:** when a Spring Boot feature does nothing at all — no error, no
log lines — suspect a missing auto-configuration module before you suspect your own code.
"No output" is a different symptom from "wrong output," and it usually means the code you
expected to run was never wired in.

---

## 7. Your machine, specifically

Things true of *this* setup that will confuse you later if undocumented.

**Port 8081, not 8080.** A Windows service holds 8080. Set in `server.port`. Every URL in
this guide uses 8081.

**Postgres is on host port 5332, not 5432.** `docker-compose.yml` maps `5332:5432` — 5332
on your machine, 5432 inside the container. When you connect from a tool on Windows, use
5332. If you ever `docker exec` into the container, use 5432.

**`POSTGRES_DB` was missing from `docker-compose.yml`.** Postgres names its default database
after `POSTGRES_USER`, so the container created `finance-app-idea`, while
`application.properties` connects to `/finance`. You must have created `finance` by hand —
both databases still exist on your volume. It is now declared explicitly, so a fresh
`docker compose up` works without manual steps. (The stray `finance-app-idea` database is
harmless; leave it or drop it, your call.)

**Maven from the terminal needs a TLS flag on this machine.** Norton Antivirus does HTTPS
scanning: it intercepts TLS connections and re-signs certificates with its own root CA.

```
issuer=OU = generated by Norton Antivirus for SSL/TLS scanning, CN = Norton Web/Mail Shield Root
```

Windows trusts that root, so browsers and `curl` work fine. But **the JDK ships its own
trust store** (`cacerts`) which does not include it, so Maven fails with
`PKIX path building failed: unable to find valid certification path`. IntelliJ works because
it manages certificates separately — which is why you never hit this.

The fix is to point the JDK at the Windows trust store, which already has Norton's root:

```powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
.\mvnw.cmd spring-boot:run
```

To stop retyping it, set it permanently for your user:

```powershell
[Environment]::SetEnvironmentVariable("MAVEN_OPTS", "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT", "User")
```

Note what we did **not** do: `-Dmaven.wagon.http.ssl.insecure=true` also makes the error go
away, by disabling certificate verification entirely. Keeping verification on and teaching
the JDK to trust the right root is the correct fix, and the habit matters more than usual
when the thing you're building is a bank.

**Two JDKs, two versions.** Your IntelliJ project SDK is `openjdk-23` (`.idea/misc.xml`
says `languageLevel="JDK_23"`), while `pom.xml` declares `<java.version>21</java.version>`.
Maven compiles to 21 either way, so nothing is broken — but IntelliJ will let you write
Java 23 syntax that Maven then rejects. Worth aligning at some point; not urgent. You also
have `graalvm-jdk-21` and `openjdk-27` installed. Terminal commands in this guide use
openjdk-23 to match the IDE:

```powershell
$env:JAVA_HOME = "C:\Users\dirtb\.jdks\openjdk-23.0.2"
```

**Versions in play**, for when you read docs: Spring Boot 4.1.1, Spring Framework 7.0.9,
Hibernate 7.4.5, Flyway 12.4.0, Postgres 18.6, springdoc 3.1.1.

Two warnings about that. **First: most Spring tutorials you find are Boot 2.x or 3.x**, and
Boot 4 removed or moved a lot. Expect `WebSecurityConfigurerAdapter`, `antMatchers()`, and
`spring-boot-starter-web` to appear in guides and not compile here. **Second: springdoc had
to be pinned to 3.1.1 by hand** because Boot does not manage its version, and springdoc
**2.8.x only works on Boot 3** — the dependency that looks current in most tutorials is the
wrong major version for you. Flyway and Testcontainers *are* managed by the Boot parent, so
they correctly have no `<version>` in your `pom.xml`.

---

## 8. How to watch it work

Four instruments. Use them constantly; they are how you replace guessing with looking.

**1. The SQL log.** Already on via `show-sql` + `format_sql`. Read the SQL for every
endpoint you write. When one request logs 50 `select`s, you have found the N+1 problem
before it reaches production.

**2. Swagger UI — `http://localhost:8081/swagger-ui.html`.** Generated from your
controllers, so it cannot drift from the code. Browse and call your own API without writing
a client. The raw document is at `/v3/api-docs`.

**3. `curl -i`.** The `-i` prints status line and headers, not just the body. Status codes
and headers *are* the API, and this is the fastest way to see them.

**4. psql.** Confirm what actually landed in the database:

```bash
docker exec -it postgres-spring-boot psql -U finance-app-idea -d finance
```

Then `\dt` to list tables, `\d+ software_engineer` to describe one, `\q` to quit. From
Milestone 3 on, "the API returned 200" and "the money moved correctly" are different
claims, and this is how you check the second one.

---

## 9. Checkpoint

You are done with Milestone 0 when all of these pass.

```powershell
# 1. From a clean start
docker compose up -d

$env:JAVA_HOME = "C:\Users\dirtb\.jdks\openjdk-23.0.2"
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
.\mvnw.cmd spring-boot:run
```

- [ ] Log shows `Successfully applied 1 migration to schema "public", now at version v1`
- [ ] App starts — meaning Hibernate `validate` found the schema and the entity agreeing
- [ ] `curl -i http://localhost:8081/api/v1/software-engineers` → `200` and `[]`
- [ ] POST one record, GET again, and see it returned
- [ ] **Restart the app and GET again — the record is still there.** This is the proof that
      `create-drop` is gone. It would not have survived before today.
- [ ] `http://localhost:8081/swagger-ui.html` loads and lists both endpoints
- [ ] `\dt` in psql shows `software_engineer` **and** `flyway_schema_history`

### Deliberately break things

Reading about an error teaches you less than causing one. Each of these takes a minute, and
each failure mode will cost you an hour later if you meet it for the first time in anger.

1. Rename the column in `V1` to `techStack`, wipe the DB, restart → `validate` fails.
   Now you know what entity/schema drift looks like.
2. Edit a comment in `V1` and restart → Flyway checksum mismatch.
3. POST without `-H "Content-Type: application/json"` → `415 Unsupported Media Type`.
4. Set `spring.jpa.show-sql=false`, make a request, and notice how blind you suddenly are.

---

## 10. Where this is going

You now have one resource with two verbs over a toy domain. Next:

- **Milestone 1** — the real domain: `customer`, `account`, `transfer`, `ledger_entry`, as
  Flyway migrations and entities. Why money is `BigDecimal`/`NUMERIC(19,4)` and never
  `double`, and why account IDs are UUIDs instead of `1, 2, 3`.
- **Milestone 2** — the full REST surface done properly: all four verbs, DTOs instead of
  raw entities, validation, real status codes, pagination. `SoftwareEngineer` gets retired
  here, once `Account` does everything it did.

**Before moving on, try narrating the GET path out loud without looking.** Any hop you
can't explain is the hop to re-read — and a question about it is a better use of the next
session than more code.
