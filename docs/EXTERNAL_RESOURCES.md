# Внешние наработки и ресурсы

Проект CodesRaider «Умный поиск для Softline Store», хакатон «ИИкузница». Реестр охватывает прямые зависимости, готовые модели, данные, ассеты и инструменты, использованные при разработке и запуске.

## Данные и материалы

- **Выгрузка Softline `Mindbox.yml.gz.xml`** — источник товаров, категорий, цен, наличия и характеристик для поиска. Полная выгрузка предоставляется отдельно. [Витрина Softline Store](https://store.softline.ru/) · [Описание импорта](../README.md#поиск-по-каталогу-softline)
- **Материалы организатора** — ТЗ, ответы на вопросы и эталонные запросы для проверки выдачи. [ТЗ](../supplementary/Technical_details_smart_search.pdf) · [Вопросы по кейсу](../supplementary/QnA_smart_search.md) · [Дополнительные разъяснения](../supplementary/userQnA.md) · [Эталонные запросы](../supplementary/SearchQueries.xlsx)
- **Softline Store и шрифт Inter** — логотип Softline и «Цифровой актив», веб-шрифты Inter Regular, SemiBold и Bold. Inter распространяется по SIL OFL 1.1. [Файлы проекта](../frontend/public/softline/) · [Лицензия Inter](https://github.com/rsms/inter/blob/master/LICENSE.txt)
- **Заглушка карточек товаров** — PNG для позиций без фотографий. Первичный источник в репозитории не указан. [Файл заглушки](../frontend/public/product-placeholder.png)
- **Официальные страницы Pantum, Pantum Shop и «Киберпротекта»** — цены для сравнения, наличие и совместимость отдельных расходников. Для цен Pantum Shop используется разметка Schema.org. [Pantum](https://www.pantum.ru/products/laser-devices/monohromnyj-printer-p2500w/) · [Pantum Shop](https://pantum-shop.ru/) · [Кибер Бэкап Старт](https://cyberprotect.ru/products/backup-start/) · [Schema.org Offer](https://schema.org/Offer)
- **Реестр Astra Linux, справка «Киберпротекта» и материалы Wondershare Recoverit** — примеры совместимости ПО и ориентиры для правил поиска по задачам резервного копирования и восстановления. [Реестр Astra](https://astra.ru/ready-for-astra/compatible-software/versions/38258/) · [Документация Кибер Бэкапа](https://docs.cyberprotect.ru/ru-RU/CyberBackup/18.0/user/schedule.html) · [Recoverit](https://recoverit.wondershare.com/)
- **Материалы по информационному поиску** — справка по лексическому и векторному отбору, опечаткам и ранжированию. [Ссылки в исследовании](SEARCH_METHODS.md)

## Готовые модели и библиотеки

- **`intfloat/multilingual-e5-small`** — готовая модель для векторного поиска по запросам и описаниям товаров, MIT. [Использованная ревизия на Hugging Face](https://huggingface.co/intfloat/multilingual-e5-small/tree/614241f622f53c4eeff9890bdc4f31cfecc418b3)
- **`Qwen2.5-1.5B-Instruct-GGUF` (`Q4_K_M`) и llama.cpp** — локальный разбор неоднозначных запросов в опциональном режиме ИИ-подбора. Лицензия весов Apache 2.0, llama.cpp — MIT. [Веса Qwen](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) · [llama.cpp](https://github.com/ggml-org/llama.cpp)
- **Sentence Transformers, PyTorch CPU, Hugging Face Hub** — вычисление эмбеддингов и загрузка весов E5. [Конфигурация модели](../model/pyproject.toml) · [Загрузка весов](../model/Dockerfile)
- **FastAPI, Pydantic, Uvicorn** — API энкодера и валидация данных. **OpenAPI, Swagger UI, ReDoc и PyYAML** — описание, просмотр и экспорт схем. **HTTPX** — тестирование API. [Зависимости](../model/pyproject.toml) · [Контракты](../contracts/)
- **Spring Boot и Spring Framework** — HTTP API, HTTP-клиент, проверки готовности и JDBC. **Jackson** — JSON, **SLF4J** — журналы. [Maven-конфигурация](../backend/pom.xml)
- **Apache Lucene** (`core`, `analysis-common`, `grouping`) — анализ текста, лексический и векторный поиск, группировка. **SQLite и Xerial SQLite JDBC** — каталог и события. **jsoup** — извлечение цен из HTML. **JUnit Jupiter** — тесты Java. [Maven-конфигурация](../backend/pom.xml)
- **React и React DOM** — интерфейс витрины. **Vite и `@vitejs/plugin-react`** — разработка и сборка. [Зависимости фронтенда](../frontend/package.json)

## Сборка, запуск и разработка

- **Eclipse Temurin 21, Apache Maven и Maven Wrapper, Node.js 24 и npm, Python 3.12 и uv** — окружения и сборка проекта. [Dockerfile приложения](../Dockerfile) · [Dockerfile модели](../model/Dockerfile) · [Maven Wrapper](../backend/.mvn/wrapper/maven-wrapper.properties)
- **Docker, BuildKit и Docker Compose** — сборка и запуск контейнеров. **Nginx и FreeDNS** — обратный прокси и DNS демонстрационного стенда. [Конфигурация запуска](../compose.yaml) · [Развёртывание](../README.md#проверка-остановка-и-диагностика) · [FreeDNS](https://freedns.afraid.org/)
- **Maven Central, npm Registry, PyPI, индекс колёс PyTorch, Docker Hub и GitHub Container Registry** — источники пакетов и контейнерных образов. [Настройки Python](../model/pyproject.toml) · [Образ llama.cpp](../compose.llm.yaml)
- **Git и GitHub** — история изменений и совместная разработка. **OpenAI Codex** — помощь с кодом, проверками и документацией. **Playwright и Chromium** — проверка интерфейса на компьютере и телефоне. [Playwright](https://playwright.dev/)
- **Ранний прототип витрины** использовал снимки HTML/CSS, иконки и изображения публичных страниц и CDN Softline, включая стили Tippy.js и PhotoSwipe. Для сбора снимков применялись `lxml` и `curl`. Итоговая витрина использует собственный React-интерфейс, логотип и Inter из ресурсов Softline. [Версия прототипа в истории](https://github.com/the-shtorm/AI_forge_CodesRaider/tree/85e83ab/frontend)

Транзитивные пакеты перечислены в [package-lock.json](../frontend/package-lock.json) и [uv.lock](../model/uv.lock). Java-зависимости разрешаются из [pom.xml](../backend/pom.xml).
