# syntax=docker/dockerfile:1
FROM node:24-bookworm-slim AS frontend-dev
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]

FROM frontend-dev AS frontend-dist
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /workspace/backend
COPY --chmod=755 backend/mvnw ./mvnw
COPY backend/.mvn/ ./.mvn/
COPY backend/pom.xml ./
COPY contracts/sql/ /workspace/contracts/sql/
COPY backend/src/ ./src/
COPY --from=frontend-dist /workspace/frontend/dist/ ./src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package

FROM eclipse-temurin:21-jre-alpine AS app
WORKDIR /app
RUN mkdir -p /app/data/snapshots /app/data/events && chown -R 10001:10001 /app/data
COPY --from=backend-build /workspace/backend/target/app.jar ./app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
