# NestJS 인증 마이그레이션

Spring의 인증 API 기본 경로는 `/v1/aideep/api/auth`입니다. 사용자·OAuth·워크스페이스 API 전체를 옮기는 작업은 아니며, 인증과 데모 가입에 필요한 기존 테이블만
사용합니다.

## 실행 설정

| 환경변수                                                                                          | 용도                                                                           |
|-----------------------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `JWT_SECRET`                                                                                  | 기존 NestJS와 동일한 HS256 secret 원문. Base64 디코딩하지 않음. UTF-8 최소 32바이트 필수           |
| `SPRING_PROFILES_ACTIVE`                                                                      | 로컬 `dev`, 배포 `prod` (미지정 기본 `dev`)                                           |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`                                                        | prod PostgreSQL JDBC URL, 사용자, 비밀번호                                          |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_DATABASE`, `REDIS_SSL` | prod Redis. 포트 6379, DB 0, SSL false 기본                                      |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_CALLBACK_URL`                             | Google OAuth 앱 설정. callback은 실제 공개 주소의 `/v1/aideep/api/auth/google/callback` |
| `FRONTEND_URL`                                                                                | 결과를 전달할 프론트엔드 origin/base URL. `/oauth/callback`을 덧붙임                        |
| `MAIL_USER`, `MAIL_PASS`                                                                      | Gmail 발송 계정과 앱 비밀번호                                                          |
| `MAIL_HOST`, `MAIL_PORT`                                                                      | 기본 `smtp.gmail.com`, 587, STARTTLS                                           |
| `MASTER_USER_IDS`                                                                             | 개발용 마스터 발급 허용 UUID, 쉼표 구분                                                    |
| `DEMO_SECRET`, `DEMO_WORKSPACE_ID`                                                            | 데모 진입 secret과 기존 워크스페이스 UUID. 미설정 시 데모 사용 불가                                 |

`dev` DB/Redis는 기존 application-dev.yml의 로컬 연결을 사용합니다. 별도 개발 DB를 쓸 경우 `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` 등 표준
Spring 환경변수로 덮어쓸 수 있습니다. `.env` 자동 로딩은 하지 않으므로 실행 환경이나 IDE에 환경변수를 주입하세요. 비밀값을 저장소에 커밋하지 않습니다.

JWT secret이 없거나 짧으면 시작을 거부합니다. 기존 secret이 32바이트 미만이면 임의로 패딩·변환해서 사용하지 말고 전환 전에 별도 키 교체 및 세션 전환을 결정해야 합니다. Google·메일 설정이
없으면 해당 기능을 호출할 때 실패하며, 일반 JWT 인증은 독립적으로 동작합니다.

## API 계약

모든 경로는 위 기본 경로 아래에 있습니다. POST 성공은 201, JSON GET/DELETE/PATCH 성공은 200, 브라우저 리다이렉트는 302입니다.

| 메서드·경로                          | 인증         | 요청/동작                                                    |
|---------------------------------|------------|----------------------------------------------------------|
| POST `/login`                   | 공개         | `email`, `password` → `accessToken`, `refreshToken`      |
| POST `/refresh`                 | 공개         | `refreshToken` → 토큰 쌍                                    |
| POST `/signup`                  | 공개         | `email`, `password`, `name`, `phone`; 이메일 인증 필수          |
| POST `/email/send`              | 공개         | `email` → `{ok:true}`. 인증번호는 응답하지 않음                     |
| POST `/email/verify`            | 공개         | `email`, 숫자 `code`                                       |
| POST `/issue/master`            | 이메일/비밀번호   | `email`, `password`; dev 전용 + UUID 허용 목록                 |
| DELETE `/logout`                | JWT        | 저장된 refresh 삭제, 전달된 JWT를 잔여 유효시간 동안 폐기                   |
| GET `/google`                   | 공개         | Google 인증으로 이동                                           |
| GET `/google/callback`          | 일회용 state  | Google code 교환, 프론트엔드 결과 페이지로 이동                         |
| POST `/oauth/signup/complete`   | 일회용 ticket | `ticket`, `username`(2~100자), `agreedToTerms:true`       |
| GET `/oauth/link/google`        | JWT        | 로그인 사용자에 대한 Google 연결 시작                                 |
| GET `/oauth/links`              | JWT        | 활성 계정의 `provider`, `email`, `created_at` 목록              |
| DELETE `/oauth/link/{provider}` | JWT        | soft delete. 마지막 로그인 수단이면 409                            |
| PATCH `/password`               | JWT        | `currentPassword`(기존 비밀번호가 있으면 필수), `newPassword`(최소 4자) |
| GET `/demo/enter?key=...`       | 데모 secret  | 매 요청마다 새 게스트를 만들고 지정 워크스페이스의 VIEWER로 참여                  |

JSON은 `{resultType,error,success}` 형식을 유지합니다. 문자열 성공도 `success`에 담습니다. 인증 업무 오류는 `AuthError`에 정의된 `AUTH_상수명` 코드와 기존 HTTP
상태·메시지를 사용하며 `error.data`는 null입니다. 인증·권한 필터 오류는 `COMMON401`·`COMMON403`, 예상하지 못한 오류는 `COMMON500`으로 통일합니다. 입력 검증 실패는
HTTP 400, `VALID400`이며 `error.data`에 필드별 메시지를 담습니다. 잘못된 JSON 등 Spring MVC 오류도 전역 핸들러의 `COMMON{status}` 규약을 사용합니다. 이는 기존
auth의 `HTTP-{status}`·`COMMON-500` 코드 및 문자열 data에서 변경된 계약이므로 프론트엔드 오류 분기를 함께 갱신해야 합니다.

리다이렉트는 `${FRONTEND_URL}/oauth/callback`에 기존 `kind=login` + 토큰 쌍, `kind=signup_required` + ticket, `kind=linked`,
`kind=error` + reason을 전달합니다. state 오류와 Google 사용자 동의 취소·프로필 검증 실패도 프론트엔드 오류 리다이렉트로 처리합니다.

## 토큰·데이터 호환

- HS256, `userName`, `email`, `user_id`, 선택적 boolean `isMaster`, `iat`, `exp`를 사용합니다.
  issuer/audience/sub/jti/token-type은 새로 요구하거나 추가하지 않습니다.
- access 15분, refresh 7일, master 30일. 사용자당 refresh는 하나이며 새 로그인은 기존 refresh를 교체합니다.
- Bearer 헤더가 우선하며, 없으면 기존 `token` 쿼리를 받습니다. 쿼리 토큰과 OAuth 리다이렉트 토큰 계약을 유지하므로 프록시/액세스 로그에 전체 쿼리나 Location을 기록하지 않도록 구성해야
  합니다.
- **사용자가 선택한 기존 규약 유지:** refresh JWT도 API 인증에 사용할 수 있습니다. 같은 사용자에게 같은 초에 발급하면 동일 토큰이 나올 수 있어, 같은 초 재발급에서는 이전 refresh
  무효화를 보장하지 않습니다. 토큰 용도 구분과 jti 도입은 별도 전환 작업입니다.
- Redis 키: `refreshToken:{userId}`, `masterToken:{userId}`, `blacklist:{token}`, `auth:{email}`, `verified:{email}`,
  `oauth:link_state:{state}`, `oauth:signup_ticket:{ticket}`. JSON 구조도 NestJS와 공유합니다.
- 인증번호 TTL 180초, 오답 5회, 인증 완료 TTL 600초. state/ticket TTL 300초이며 일회용입니다.
- DB 테이블: `users`, `oauth_accounts`, `workspaces`, `users_workspaces`; 기존 UUID·timestamp·bcrypt·유일성 제약조건을 사용합니다. `phone`
  은 기존처럼 입력만 받고 저장하지 않습니다.
- DDL 자동 변경은 꺼져 있습니다. 신규 DB라면 기존 서비스의 스키마를 먼저 준비해야 합니다. `src/test/resources/auth-schema.sql`은 테스트용 최소 스키마이며 운영 마이그레이션이
  아닙니다.
- 동일 사용자의 동일 Google 계정 재연결은 삭제된 행을 복구합니다. 기존 전체 행 unique 제약 때문에 삭제된 OAuth 계정을 다른 사용자로 옮기거나 같은 provider의 다른 계정으로 교체하는 경우
  409가 날 수 있으며, 스키마 변경 없이 이를 우회하지 않습니다.
- 비밀번호 변경 후 기존 토큰은 기존 NestJS와 동일하게 유지됩니다. DB와 Redis 간 분산 트랜잭션은 없으므로 DB 커밋 후 토큰 저장이 실패하면 계정은 생성되어 있을 수 있습니다. 재로그인으로 복구할 수
  있습니다.

## 운영·로그

Spring 인증은 stateless이며 Swagger와 명시된 공개 인증 경로 외에는 인증이 필요합니다. 기존 `/api/**` 전체 공개는 제거했습니다. master 권한은
`@PreAuthorize("hasRole('MASTER')")`로 제한할 수 있습니다.

예상 가능한 인증 오류는 DEBUG, 예상하지 못한 예외/저장소 장애는 ERROR + 스택 트레이스입니다. 개발에서는 둘 다 출력하고 운영에서는 auth의 ERROR만 출력합니다. 일반 애플리케이션 로그는 INFO를
유지합니다. 애플리케이션 로거에 비밀번호·JWT·인증번호를 넣지 않습니다.

배포 시 동일 DB·Redis DB 번호·secret을 주입하고, 기존 공개 API 경로와 Google 등록 callback URL이 Spring에 도달하도록 라우팅합니다. auth 경로 묶음을 Spring으로
전환하고 이전 서버로 되돌릴 수 있게 유지하세요. NestJS가 계속 토큰을 발급해도 Spring은 검증하지만, 기존 NestJS의 재발급 경쟁 조건까지 개선되지는 않습니다. 실제 배포나 비밀값 변경은 이 구현에서
수행하지 않습니다.

## 검증

`./gradlew test`는 Docker가 필요합니다. Testcontainers의 독립 PostgreSQL·Redis, 로컬 Google HTTP 서버 및 SMTP 서버로 검증하며 개발/운영 DB 또는 실제
메일 계정을 사용하지 않습니다.

테스트는 기존 Node bcrypt 해시, 독립 HS256 fixture, 서명/만료/필수 claim 검증, refresh·logout·master, 이메일 인증, OAuth state/ticket·연결·해제·롤백,
비밀번호, 데모 VIEWER, 응답 포맷·Swagger·프로필별 로그를 확인합니다. `build/test-jwt-compat.json`은 테스트용 secret으로 발급한 토큰이며 실제 자격증명이 아닙니다.
