FROM adoptopenjdk:12-jre-hotspot
RUN mkdir /Ramsey
COPY target/ramsey-queue-manager.jar /Ramsey/
COPY target/version.txt /Ramsey/
CMD java -jar -Dspring.profiles.active=${SPRING_PROFILE} /Ramsey/ramsey-queue-manager.jar
