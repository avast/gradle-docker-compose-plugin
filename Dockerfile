# This is Dockerfile that defines build environment.
FROM java:8-jdk
MAINTAINER augustyn@avast.com

# install Docker
ENV DOCKER_VERSION=1.10.2
RUN curl -sSL -O https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION} \
    && chmod +x docker-${DOCKER_VERSION}
    && mv docker-${DOCKER_VERSION} /usr/local/bin/docker

# install docker-compose
ENV COMPOSE_VERSION 1.6.2
RUN curl -o /usr/local/bin/docker-compose -L "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-Linux-x86_64" \
	&& chmod +x /usr/local/bin/docker-compose

# allow to bind local Docker to the outer Docker
VOLUME /var/run/docker.sock

VOLUME /build
WORKDIR /build

ENTRYPOINT ["/build/gradlew"]
CMD ["test"]
