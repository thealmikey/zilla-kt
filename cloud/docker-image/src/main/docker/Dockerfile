FROM eclipse-temurin:22-jdk AS build
RUN apt-get update && apt-get install -y ca-certificates
COPY maven /root/.m2/repository
ENV HOME /root
WORKDIR /build
COPY zpmw zpmw
COPY zpm.json.template zpm.json.template
RUN mkdir -p /build/zilla
RUN cat zpm.json.template | sed "s/\${VERSION}/${project.version}/g" | tee zpm.json
RUN ./zpmw install
#RUN ./zpmw clean --keep-image

FROM ubuntu:jammy-20250404

ENV ZILLA_VERSION=${project.version}

COPY --from=build /build/.zpm /opt/zilla/.zpm
COPY --from=build /build/zilla /opt/zilla/zilla
COPY zilla.properties /opt/zilla/.zilla/zilla.properties

ENTRYPOINT ["/opt/zilla/zilla"]