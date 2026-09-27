# Spring 앱 Docker 이미지

프로젝트 루트에 `.env`를 준비한 뒤 Compose로 빌드하고 실행합니다. 필요한 환경 변수 이름은
`.env.example`을 참고하세요. `.env`는 저장소에 커밋하지 않습니다.

```sh
docker compose up --build -d
```

Compose는 `aideep-app-spring:latest` 이미지를 만들고 앱을 운영 프로필로 실행합니다.
`DB_URL`과 `REDIS_HOST`에는 컨테이너에서 접근 가능한 PostgreSQL·Redis 주소를 지정해야 합니다.
`.env.example`의 호스트 이름은 실제 접속 가능한 주소로 바꿔야 합니다.

로그 확인과 종료:

```sh
docker compose logs -f aideep-app-spring
docker compose down
```

빌드 단계는 Gradle Wrapper로 `bootJar`를 실행합니다. 실행 이미지에는 Java 21 JRE와 `app.jar`가 들어갑니다.
빌드 시 테스트는 실행하지 않으므로 별도로 `./gradlew test`를 실행합니다. 메일, OAuth, Recall 기능을 사용한다면
해당 환경 변수도 `.env`에 설정합니다. 설정 키는 `src/main/resources/application.yml`과
`application-prod.yml`에서 확인할 수 있습니다.
