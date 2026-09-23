#!/usr/bin/env bash
# Dor 3 do deck "De semanas a horas" (TDC São Paulo 2026).
# Rodar na raiz do clone do DayTrader 8 (github.com/OpenLiberty/sample.daytrader8),
# com o pom limpo, ainda em 1.8: o OpenRewrite precisa do classpath resolvível.
set -e

PLUGIN=org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run
RECIPES=org.openrewrite.recipe:rewrite-migrate-java:3.42.1
MIGRATE=org.openrewrite.java.migrate

mvn -B $PLUGIN -Drewrite.recipeArtifactCoordinates=$RECIPES \
  -Drewrite.activeRecipes=$MIGRATE.jakarta.JakartaEE10

mvn -B $PLUGIN -Drewrite.recipeArtifactCoordinates=$RECIPES \
  -Drewrite.activeRecipes=$MIGRATE.UpgradeToJava25
