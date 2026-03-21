# TradeJournal

TradeJournal la ung dung web ghi nhat ky giao dich duoc xay bang Spring Boot + Thymeleaf. Muc tieu cua app la giup trader luu trade, review qua trinh, theo doi hieu suat, nhan AI insight, va quan ly tai khoan tren cung mot workspace.

## Tong quan nhanh

- Quan ly trade, setup, mistake tag va hinh anh trade.
- Dashboard, analytics, reports va weekly coach report de theo doi performance.
- AI screenshot import tu TradingView khi co `OPENAI_API_KEY`.
- AI review cho trade detail de danh gia process va quality.
- Billing, settings, export du lieu va xoa tai khoan.
- Khu admin rieng cho user management, analytics, billing va reports.

## Cong nghe chinh

- Java 21
- Spring Boot 4
- Spring MVC + Thymeleaf
- Spring Data JPA / Hibernate
- SQLite cho local profile
- PostgreSQL cho moi truong deploy / default profile
- Bootstrap, Chart.js
- OpenAI API cho screenshot import va AI review

## Cac khu chuc nang chinh

- `Auth`: login, register, logout
- `Trades`: tao/sua/xoa trade, import trade, upload hinh anh, trade detail
- `Analytics`: dashboard, analytics page, reports, weekly coaching report
- `Setups` va `Mistakes`: quan ly setup va loi giao dich
- `Settings`: profile, password, preferences, notifications, export, delete account
- `Billing`: plan, invoices, webhook Stripe
- `Admin`: dashboard, users, billing, analytics, reports, settings

## Chay local

### Yeu cau

- Java 21
- Maven wrapper co san trong repo

### Cach chay nhanh

1. Tao file env local:

```powershell
Copy-Item .env.local.example .env.local
```

2. Cap nhat bien moi truong trong `.env.local`.

- `OPENAI_API_KEY` la optional. Neu khong co thi cac tinh nang AI se bi tat.

3. Chay app:

```powershell
./run-local.ps1
```

4. Mo trinh duyet:

```text
http://localhost:8082
```

`run-local.ps1` se:

- Nap bien trong `.env.local`
- Bat profile `local`
- Dat port mac dinh `8082` neu chua khai bao
- Chay `mvnw.cmd spring-boot:run`

## Cau hinh moi truong

### Local profile

- File cau hinh: `src/main/resources/application-local.yml`
- DB local: `data/trading_journal.db`
- Port mac dinh: `8082`
- Database: SQLite

### Default / deploy profile

- File cau hinh: `src/main/resources/application.properties`
- Database mac dinh: PostgreSQL qua env vars
- Port mac dinh: `PORT` hoac fallback `8081`

### Bien moi truong quan trong

- `OPENAI_API_KEY`: bat AI screenshot import va AI review
- `OPENAI_BASE_URL`: custom OpenAI-compatible endpoint neu can
- `BILLING_WEBHOOK_SECRET`: xac thuc webhook billing
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `LOCAL_SERVER_PORT`
- `ADMIN_EMAIL`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`: bootstrap admin account

## Lenh huu ich

```powershell
./mvnw.cmd test
./mvnw.cmd -DskipTests compile
./mvnw.cmd package
```

## Cau truc thu muc

```text
src/main/java/com/example/demo
  controller/   MVC controllers va API endpoints
  service/      business logic, analytics, AI, billing, settings
  entity/       JPA entities
  repository/   Spring Data repositories
  config/       datasource, bootstrap, web config

src/main/resources
  templates/    Thymeleaf pages
  static/       CSS, JS, images
  application.properties
  application-local.yml

data/
  trading_journal.db   SQLite local database
```

## Ghi chu

- `.env.local` nen giu tren may local va khong commit secret.
- Neu khong set `OPENAI_API_KEY`, app van chay binh thuong nhung cac tinh nang AI se khong hoat dong.
- README nay la ban tom tat so bo; co the mo rong them setup deploy, test flow, database schema, va user roles sau.
