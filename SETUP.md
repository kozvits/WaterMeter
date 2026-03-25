# Первоначальная настройка проекта

## Почему нужен этот шаг

Файл `gradle/wrapper/gradle-wrapper.jar` — бинарный файл, необходимый для работы
Gradle Wrapper. Он **должен присутствовать в репозитории**, иначе CI-сборка упадёт
с ошибкой `Could not find GradleWrapperMain`.

Этот файл не включён в архив (бинарники не хранятся в системе контроля версий по умолчанию),
поэтому его нужно сгенерировать **один раз** перед первым `git push`.

---

## Способ 1 — через Android Studio (рекомендуется)

1. Откройте проект в Android Studio
2. Дождитесь завершения Gradle Sync
3. Android Studio автоматически создаст `gradle/wrapper/gradle-wrapper.jar`
4. Закоммитьте его:

```bash
git add gradle/wrapper/gradle-wrapper.jar
git commit -m "Add gradle-wrapper.jar"
git push
```

---

## Способ 2 — через командную строку (если Gradle установлен локально)

```bash
# Проверьте, что Gradle установлен
gradle --version

# Сгенерируйте wrapper
gradle wrapper --gradle-version 8.2

# Закоммитьте
git add gradle/wrapper/gradle-wrapper.jar gradlew gradlew.bat
git commit -m "Add gradle wrapper"
git push
```

---

## Способ 3 — скачать jar вручную (если нет Android Studio и Gradle)

```bash
mkdir -p gradle/wrapper

curl -fsSL \
  "https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar" \
  -o gradle/wrapper/gradle-wrapper.jar

git add gradle/wrapper/gradle-wrapper.jar
git commit -m "Add gradle-wrapper.jar"
git push
```

---

## Проверка

После того как `gradle-wrapper.jar` закоммичен, GitHub Actions должен успешно
выполнить сборку. Статус можно отслеживать на вкладке **Actions** репозитория.
