# 검토한 Java 25 JDK 이미지의 sha256 digest를 필수로 전달합니다. 자동 태그 선택/다운로드 스크립트는 없습니다.
ARG JAVA25_IMAGE
FROM ${JAVA25_IMAGE}
COPY src/main/java/io/github/jesterportfolio/hearth/administration/OperatorLaunchCheck.java /opt/hearth/OperatorLaunchCheck.java
RUN javac -d /opt/hearth/classes /opt/hearth/OperatorLaunchCheck.java
COPY infrastructure/deployment/paper-entrypoint.sh /opt/hearth/paper-entrypoint.sh
WORKDIR /data
ENTRYPOINT ["/bin/sh", "/opt/hearth/paper-entrypoint.sh"]
