# This is Dockerfile that defines build environment.
FROM openjdk:8u171-jdk-alpine
MAINTAINER augustyn@avast.com

ARG DOCKER_COMPOSE_VERSION=1.22.0
ENV DOCKER_COMPOSE_VERSION=$DOCKER_COMPOSE_VERSION

RUN apk add --update libstdc++ docker=18.03.1-r0 py2-pip=10.0.1-r0 && rm -rf /var/cache/apk/* && pip install --no-cache-dir docker-compose==$DOCKER_COMPOSE_VERSION

# allow to bind local Docker to the outer Docker
VOLUME /var/run/docker.sock

VOLUME /build
WORKDIR /build

ENTRYPOINT ["/build/gradlew"]
CMD ["test"]
