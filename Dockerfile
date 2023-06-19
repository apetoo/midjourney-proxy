FROM eclipse-temurin:17-jre

ARG user=spring
ARG group=spring

ENV SPRING_HOME=/home/spring

RUN groupadd -g 1000 ${group} \
	&& useradd -d "$SPRING_HOME" -u 1000 -g 1000 -m -s /bin/bash ${user} \
	&& mkdir -p $SPRING_HOME/config \
	&& mkdir -p $SPRING_HOME/logs \
	&& chown -R ${user}:${group} $SPRING_HOME/config $SPRING_HOME/logs

# Railway 不支持使用 VOLUME, 本地需要构建时，取消下一行的注释
# VOLUME ["$SPRING_HOME/config", "$SPRING_HOME/logs"]

USER ${user}
WORKDIR $SPRING_HOME

ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8080

ENV JAVA_OPTS -XX:MaxRAMPercentage=85 -Djava.awt.headless=true -XX:+HeapDumpOnOutOfMemoryError \
 -XX:MaxGCPauseMillis=20 -XX:InitiatingHeapOccupancyPercent=35 -Xlog:gc:file=/home/spring/logs/gc.log \
 -Dlogging.file.path=/home/spring/logs \
 -Dserver.port=8080 -Duser.timezone=Asia/Shanghai

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar $SPRING_HOME/app.jar"]
