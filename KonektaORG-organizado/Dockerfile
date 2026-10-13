FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY . /app
ENV PORT=8080 KONEKTA_PRODUCAO=true KONEKTA_DEMO=false
EXPOSE 8080
CMD ["java", "src/Konekta.java"]
