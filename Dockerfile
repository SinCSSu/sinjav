# syntax=docker/dockerfile:1
# 多阶段构建：前端 dist 并入 Spring Boot 静态资源（页面与 API 同源，单容器部署）

# ---------- 阶段 1：前端构建（Vite 8 需要 Node 20.19+/22+） ----------
FROM node:22-alpine AS web-build
WORKDIR /web
# 先拷贝依赖清单利用层缓存
COPY sinjav_web/package.json sinjav_web/package-lock.json ./
RUN npm ci
COPY sinjav_web/ ./
RUN npm run build

# ---------- 阶段 2：后端构建（JDK 17 + Gradle 9 wrapper） ----------
FROM eclipse-temurin:17-jdk AS app-build
WORKDIR /app
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY src/ src/
# 前端产物放入 Boot 静态目录：访问 http://host:8656/ 即 Web 界面
COPY --from=web-build /web/dist/ src/main/resources/static/
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon -q

# ---------- 阶段 3：运行镜像 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=app-build /app/build/libs/*.jar app.jar
# 数据卷：SQLite 数据库 ./data/sinjav.db + 本地图片 ./data/images
VOLUME ["/app/data"]
EXPOSE 8656
# JVM 内存按容器限制自适应；额外参数可用 JAVA_TOOL_OPTIONS 环境变量传入
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
