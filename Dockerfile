FROM adoptopenjdk:12-jre-hotspot
RUN mkdir /Ramsey
COPY target/ramsey-queue-manager.jar /Ramsey/
COPY target/version.txt /Ramsey/
CMD java -jar /Ramsey/ramsey-queue-manager.jar --spring.profiles.active=${SPRING_PROFILE}
