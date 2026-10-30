# =============================================================================
# RAF - Image du backend
#
# Deux etapes : Maven compile le jar, puis seul le JRE et le jar passent dans
# l'image finale. Les tests ne tournent pas ici (ils exigent une base MySQL).
# =============================================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Les dependances changent moins souvent que le code : leur telechargement
# reste en cache tant que le pom ne bouge pas.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/raf-*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app

# Jamais root dans le conteneur.
RUN groupadd --system raf && useradd --system --gid raf raf
COPY --from=build /build/app.jar app.jar
# Dossier des images : un volume monte ici pour la premiere fois en reprend
# le proprietaire.
RUN mkdir -p /data/media && chown raf:raf /data/media
USER raf

ENV APP_ENV=prod \
    SERVER_PORT=8081 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8081

# Lu par Docker et par Coolify (Traefik n'envoie rien a un conteneur en echec).
# Ni curl ni wget dans l'image : requete HTTP a la main avec bash.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
