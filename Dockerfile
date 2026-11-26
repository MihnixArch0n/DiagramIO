# ---- Giai đoạn 1: build ra file jar bằng Gradle Wrapper ----
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

# Copy wrapper và cấu hình dependencies để tận dụng Docker cache layer
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./

RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon

COPY src ./src
# Build bootJar bỏ qua test (test đã được chạy riêng trên CI / host)
RUN ./gradlew bootJar -x test --no-daemon

# ---- Giai đoạn 2: image chạy, chỉ có JRE + jar ----
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Không chạy app bằng root; tạo sẵn thư mục lưu trữ blob và cấp quyền
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data/blobs \
    && chown -R app:app /app/data
USER app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
