# This is Dockerfile that defines build environment.
FROM java:8u111-jdk-alpine
MAINTAINER augustyn@avast.com

RUN apk add --update libstdc++ docker=1.11.2-r1 py-pip=8.1.2-r0 && rm -rf /var/cache/apk/* && pip install --no-cache-dir docker-compose==1.11.2

# allow to bind local Docker to the outer Docker
VOLUME /var/run/docker.sock

VOLUME /build
WORKDIR /build

ENTRYPOINT ["/build/gradlew"]
CMD ["test"]
