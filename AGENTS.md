# 코드 작성 지침

## 도메인 기반 구조

- 현재 프로젝트는 도메인 기반 패키지 구조를 사용한다. 운영 코드는 원칙적으로
  `src/main/java/com/aideep/domain/<domain>` 아래에 둔다.
- 각 도메인은 필요한 계층을 `controller`, `service`, `repository`, `entity`, `dto`, `exception`, `config`,
  `security` 등의 하위 패키지로 나눈다. 사용하지 않는 계층을 형식적으로 만들지 않는다.
- 컨트롤러는 요청 검증과 응답 변환 및 서비스 호출을 담당하고, 비즈니스 규칙은 서비스나 엔티티에 둔다. 저장소는 영속성 접근에
  집중한다.
- 요청 DTO와 응답 DTO는 각각 `dto/request`, `dto/response`에 둔다. 외부 API 계약과 내부 도메인 객체를 불필요하게 결합하지
  않는다.
- 둘 이상의 도메인에서 실제로 공유하는 설정, 응답 형식, 예외 처리, 기반 엔티티만 `com.aideep.global`에 둔다. 특정 도메인의
  비즈니스 규칙이나 편의를 위한 코드를 미리 `global`로 올리지 않는다.
- JPA 엔티티는 `com.aideep.global.entity.BaseEntity`를 상속해 공통 ID와 생성·수정·삭제 시각을 사용한다. 기존 테이블명,
  컬럼명과 제약 조건이 다르면 `@AttributeOverride` 등 명시적인 매핑으로 DB 계약을 유지한다.
- 다른 도메인의 `entity`나 `repository`를 직접 참조하는 결합은 피한다. 도메인 간 협력이 필요하면 대상 도메인의 서비스나
  명시적인 인터페이스 및 DTO를 경계로 사용한다.
- 변경은 요청받은 도메인 안에 응집시키고, 무관한 도메인의 리팩터링이나 이름 변경을 같은 작업에 섞지 않는다.

## 포맷

- `.idea/codeStyles/Project.xml`에 저장한 SofteerStyle을 기준으로 한다. 프로젝트 코드 스타일을 사용하며 개인 IDE 설정으로 덮어쓰지 않는다.
- 직접 작성한 소스, 테스트, 설정, 문서에 해당 파일 형식의 IntelliJ 포맷을 적용한다. 생성 파일, Gradle Wrapper, 바이너리, 빌드 산출물은 제외한다.
- Java는 공백 4칸 들여쓰기, 기본 줄 너비 120자를 사용한다. 줄바꿈, 연속 행 정렬, 중괄호, 빈 줄과 주석 처리는 공유 설정을 따른다. 문자열 리터럴이나 외부 계약을 줄 너비에 맞추려고 변경하지 않는다.
- import는 static 그룹을 먼저 두고 빈 줄로 일반 그룹과 구분하며 각 그룹을 정렬한다. 와일드카드 import를 사용하지 않는다.
- Google Java Format 등 다른 포맷터로 대체하지 않는다. IntelliJ의 Reformat Code와 Optimize Imports를 사용한다.

## 변수와 주입 인자 이름

- 서비스, 저장소, 클라이언트, 설정 객체 등 의존성 필드와 주입 인자는 선언 타입 이름의 첫 글자만 소문자로 바꿔 사용한다. 테스트의 mock과 같은 의존성을 담는 지역 변수에도 적용한다.
- 예: `AuthService authService`, `AuthUserRepository authUserRepository`, `RedisAuthStore redisAuthStore`,
  `JwtTokenService jwtTokenService`, `AuthProperties authProperties`, `OAuthService oAuthService`,
  `OAuthAccountRepository oAuthAccountRepository`.
- `users`, `store`, `tokens`, `properties`, `service`처럼 타입을 알 수 없는 축약명은 피한다. 생성자 인자와 필드 이름을 일치시킨다.
- 공급자는 대상 타입과 역할을 함께 쓴다. 예: `ObjectProvider<JavaMailSender> javaMailSenderProvider`.
- 동일 타입 객체를 구분해야 하면 역할 접두어를 붙인다. 예: `deniedMockMvc`, `googleHttpServer`.
- 데이터 값과 DTO·엔티티 필드는 의미를 나타내는 이름을 유지한다. `email`, `userId`, `accessToken` 등을 단순히 타입 이름으로 바꾸지 않는다.
- 이 규칙은 메서드 이름을 클래스 이름으로 바꾸라는 뜻이 아니다. 메서드는 동작을 표현하는 기존 이름을 유지한다.

## 테스트 구성

- 운영 코드를 변경하거나 새 기능을 추가하면 해당 동작을 검증하는 테스트를 함께 작성한다. 단순 컴파일만으로 완료로 간주하지
  않는다.
- 테스트 패키지는 운영 코드의 도메인과 패키지 구조를 그대로 따른다. 예를 들어 인증 도메인 테스트는
  `src/test/java/com/aideep/domain/auth` 아래에 두고, 전역 기능 테스트만 `src/test/java/com/aideep/global` 아래에 둔다.
- 여러 도메인의 테스트를 루트 패키지나 하나의 거대한 테스트 클래스에 계속 추가하지 않는다. 기존 통합 테스트를 수정해야 할 때도
  가능하면 컨트롤러, 서비스, 저장소, 엔티티 등 책임과 도메인에 맞는 테스트 파일로 분리한다.
- 단위 테스트는 대상 클래스 또는 정책 단위로 `*Test` 이름을 사용한다. Spring Context, DB, Redis, 외부 서버를 함께 사용하는
  테스트는 범위를 드러내는 `*IntegrationTest` 이름을 사용한다.
- 엔티티 변경에는 생성 규칙, 상태 변경, 공통 필드 갱신을 검증하는 테스트를 작성한다. 저장소나 매핑 변경에는 실제 컬럼명,
  제약 조건, 조회 조건을 검증하는 영속성 테스트를 작성한다.
- 테스트 픽스처와 리소스도 도메인별로 분리한다. 공용 픽스처는 둘 이상의 도메인에서 재사용되는 경우에만 전역 테스트 유틸리티로
  승격한다.
- 테스트는 서로 실행 순서와 공유 상태에 의존하지 않아야 한다. 시간, UUID, 외부 서버 응답처럼 결과를 불안정하게 만드는 값은
  고정하거나 주입한다.
- 정상 흐름뿐 아니라 검증 실패, 권한 부족, 중복 데이터, 삭제 상태 등 변경과 관련된 주요 실패 흐름도 함께 검증한다.

## 커밋 금지 파일

- `.env`, `.env.*`, `application-local.yml`처럼 비밀값이나 개인 로컬 환경값을 포함하는 파일은 커밋하지 않는다. 공유가 필요한
  키 목록은 실제 값 없이 `.env.example` 같은 예시 파일에 작성한다.
- API 키, 비밀번호, 토큰, 개인 인증서, private key, 클라우드 credential JSON 등 자격 증명은 파일 형식이나 위치와 관계없이
  커밋하지 않는다.
- `build/`, `.gradle/`, `out/`, `bin/`, 테스트 리포트, 커버리지 결과처럼 빌드나 테스트가 생성한 산출물은 커밋하지 않는다.
  Gradle Wrapper 자체(`gradlew`, `gradlew.bat`, `gradle/wrapper/*`)는 이 금지 대상에서 제외한다.
- `.idea/workspace.xml`, HTTP Client 기록과 쿠키, 개인 플러그인 설정, `*.iml`, `.vscode/`, `.DS_Store` 등 개인 IDE·OS 상태는
  커밋하지 않는다. 팀이 합의한 `.idea/codeStyles/Project.xml`과 `.idea/codeStyles/codeStyleConfig.xml`만 예외로 공유할 수
  있다.
- PostgreSQL의 `pgdata`, Redis의 `data`, 덤프, AOF, 로그 등 로컬 컨테이너와 DB가 만든 런타임 데이터는 커밋하지 않는다.
  재현에 필요한 Docker Compose와 초기화 스크립트는 비밀값이 없는지 확인한 뒤 커밋할 수 있다.
- 임시 파일, 에디터 백업 파일, 로컬 캐시, 디버깅 출력물은 커밋하지 않는다. 작업 전후 `git status --short`로 신규 파일을
  확인하고 커밋 대상만 포함한다.
- 이미 작업 트리에 존재하는 다른 사람의 변경과 사용자 작업은 삭제하거나 되돌리거나 함께 커밋하지 않는다.

## 변경 및 검증

- 포맷·명명 정리에서는 기존 작업을 보존하고 API URL, JSON 키, DB 매핑, 설정 키·값, 동작을 변경하지 않는다.
- 변수 이름을 바꿀 때 생성자, 호출부, 테스트 참조를 함께 갱신하고 문자열·패키지명·메서드명에 단순 치환이 적용되지 않도록 확인한다.
- 작업을 마치기 전에 변경된 운영 코드와 같은 도메인의 테스트가 추가되거나 갱신됐는지 확인한다. 테스트를 작성할 수 없는 변경이면
  그 이유와 수동 검증 방법을 결과에 명시한다.
- `./gradlew test`로 컴파일과 기존 테스트를 검증한다. 통합 테스트는 Docker의 PostgreSQL·Redis 컨테이너와 로컬 테스트 서버를 사용한다. 실행하지 못한 테스트나 기존 실패는 명확히
  보고한다.
- `git diff --check`로 공백 오류를 확인하고, 포맷을 재적용했을 때 추가 변경이 없는지 확인한다.
- IntelliJ CLI 포맷 예시(macOS):

  ```sh
  "/Applications/IntelliJ IDEA.app/Contents/bin/format.sh" \
    -s .idea/codeStyles/Project.xml -r \
    -m '*.java,*.yml,*.sql,*.gradle,*.md,*.properties' \
    src docs build.gradle settings.gradle AGENTS.md
  ```

  검증만 하려면 `-d`를 추가한다. IDE가 실행 중이면 별도 임시 디렉터리를 지정한 `IDEA_PROPERTIES` 파일의 `idea.config.path`, `idea.system.path`,
  `idea.log.path`, `idea.plugins.path`로 CLI 실행 환경을 분리한다. 개인 경로와 임시 캐시는 저장소에 추가하지 않는다.
