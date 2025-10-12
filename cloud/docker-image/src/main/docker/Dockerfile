# --------------------------------------------------------------------
# Stage 1: Build and assemble using Maven 3.9.9 with Temurin JDK 22
# --------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-22-jammy AS build

LABEL maintainer="Aklivity Inc."
LABEL description="Builds Zilla Manager using Maven 3.9.9 + Temurin JDK 22"

# Verify the Maven + JDK versions so CI logs clearly show environment consistency
RUN mvn --version && java --version

# (Optional) preload certificates or root CAs if required
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*

# Set work directory
WORKDIR /workspace

# Copy Maven repository cache (if available) to speed up resolution
COPY maven /root/.m2/repository

# Copy wrapper script and zpm definition
COPY zpmw zpmw
COPY zpm.json zpm.json

# Ensure wrapper uses the correct Maven version
COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/maven-wrapper.properties

# Confirm the wrapper is set for 3.9.9 (useful debug output)
RUN grep "3.9.9" .mvn/wrapper/maven-wrapper.properties || \
    (echo "ERROR: Maven Wrapper not pinned to 3.9.9" && exit 1)

# Replace ${project.version} tokens inside zpm.json if present
RUN sed -i "s/\${VERSION}/${project.version}/g" zpm.json

# Optional sanity check on local repo contents
RUN ls -alh /root/.m2/repository/io/aklivity/zilla || true

# --------------------------------------------------------------------
# Execute Zilla Manager install/clean using zpmw (which wraps the jar)
# --------------------------------------------------------------------
RUN ./zpmw install && \
    ./zpmw clean --keep-image

# --------------------------------------------------------------------
# Stage 2: Runtime image (minimal, no Maven)
# --------------------------------------------------------------------
FROM eclipse-temurin:22-jdk-jammy AS runtime

LABEL maintainer="Aklivity Inc."
LABEL description="Zilla runtime container built with zpm"

ENV ZILLA_VERSION=${project.version}

# Create expected directories
RUN mkdir -p /opt/zilla/.zilla /opt/zilla/.zpm

# Copy runtime artifacts from build stage
COPY --from=build /workspace/.zpm /opt/zilla/.zpm
COPY --from=build /workspace/.zpm/zilla /opt/zilla/zilla
COPY zilla.properties /opt/zilla/.zilla/zilla.properties

# Set default working directory and entrypoint
WORKDIR /opt/zilla
ENTRYPOINT ["/opt/zilla/zilla"]
