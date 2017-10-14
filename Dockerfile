# This is Dockerfile that defines build environment.
FROM openjdk:8u131-jdk-alpine
MAINTAINER augustyn@avast.com

ARG DOCKER_COMPOSE_VERSION=1.15.0
ENV DOCKER_COMPOSE_VERSION=$DOCKER_COMPOSE_VERSION

RUN apk add --update libstdc++ docker=17.05.0-r0 py2-pip=9.0.1-r1 && rm -rf /var/cache/apk/* && pip install --no-cache-dir docker-compose==$DOCKER_COMPOSE_VERSION

# allow to bind local Docker to the outer Docker
VOLUME /var/run/docker.sock

VOLUME /build
WORKDIR /build

ENTRYPOINT ["/build/gradlew"]
CMD ["test"]
