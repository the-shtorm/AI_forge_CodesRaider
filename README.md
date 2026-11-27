# Умный поиск для Softline Store

Прототип для трека «Умный поиск на сайте» хакатона «ИИкузница». Витрина ищет ПО и оборудование по артикулу, названию и описанию задачи. Пользователь может уточнить результаты фильтрами, открыть товар или семейство, посмотреть совместимые дополнения и обратиться к ИИ-помощнику. Для поддерживаемых артикулов карточка сравнивает цены каталога и вендора. Аналитика показывает ход поисковых сессий. Корзина и пробная покупка предназначены для демонстрации.

React и JavaScript работают с API Spring Boot на Java 21. Lucene хранит поисковый индекс, SQLite — каталог и события. FastAPI с E5 добавляет векторный отбор, а Qwen через llama.cpp разбирает неоднозначные запросы при включённой конфигурации. [Архитектура, функции и результаты проверки](RESEARCH.md)

## Требования и клонирование

Понадобятся Git, Docker Engine или Docker Desktop и Docker Compose v2.20+ с BuildKit. Первая сборка загружает базовые образы и зависимости. Java 21, Node.js 24 и Python 3.12 устанавливаются в образы тех сервисов, которым они нужны. Для сценариев проверки с хоста нужен Python 3.

```bash
git clone https://github.com/the-shtorm/AI_forge_CodesRaider.git
cd AI_forge_CodesRaider
git switch master
docker compose version
```

Для закрытого репозитория используй аккаунт с доступом к нему. [Клонирование и авторизация GitHub](https://docs.github.com/en/get-started/git-basics/about-remote-repositories)

## Быстрый запуск с демокаталогом

Из корня репозитория запусти:

```sh
docker compose -p softline-demo up -d --build --wait app
```

Spring отдаёт собранную витрину и API на одном порту. При первом запуске с пустым томом импортируется встроенный XML из 35 предложений, которые группируются в 30 карточек. Если в этом Compose-проекте уже есть активный снимок, приложение откроет его. [Витрина](http://127.0.0.1:8080/) · [Проверка готовности](http://127.0.0.1:8080/actuator/health/readiness)

Перед запуском второго экземпляра на том же порту останови демостенд командой `docker compose -p softline-demo down`. Чтобы запустить оба параллельно, задай второму экземпляру другой `APP_PORT`.

## Поиск по каталогу Softline

Полная выгрузка `Mindbox.yml.gz.xml` предоставляется отдельно. Импортёр принимает XML/YML и определяет gzip по содержимому. Приведённые команды запускают лексический поиск без обязательных модельных сервисов.

### Linux и VM

Скопируй файл на VM и используй его абсолютный путь. Например, из PowerShell на Windows:

```powershell
Set-Location "$env:USERPROFILE/Downloads"
scp ./Mindbox.yml.gz.xml user@VM_IP:~/
```

На Linux или VM, из корня клона:

```bash
FEED="$(realpath "$HOME/Mindbox.yml.gz.xml")"
export SEED_DEMO_CATALOG=false

docker compose -p softline build app
docker compose -p softline run --rm --no-deps \
  --volume "$FEED:/catalog.xml:ro" app import
```

Дождись строки `Activated catalog snapshot` и успешного завершения импорта. Затем запусти приложение:

```bash
docker compose -p softline up -d --force-recreate --wait app
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

### Windows PowerShell

Из корня клона:

```powershell
$feed = (Resolve-Path "$env:USERPROFILE/Downloads/Mindbox.yml.gz.xml").Path
$env:SEED_DEMO_CATALOG = 'false'

docker compose -p softline build app
docker compose -p softline run --rm --no-deps --volume "${feed}:/catalog.xml:ro" app import
```

После успешного импорта:

```powershell
docker compose -p softline up -d --force-recreate --wait app
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

Файл передаётся в контейнер только для чтения. Compose-проект `softline` хранит снимки в томе `snapshots`, события — в `events`. Используй одинаковый `-p softline` для импорта, запуска и диагностики. Spring открывает активный снимок при старте, поэтому после повторного импорта пересоздай `app` или выполни `docker compose -p softline restart app`. После перехода на рецепт `catalog-v1.5` повтори импорт полного фида, чтобы использовать все возможности иерархического поиска. Проверить выбранный снимок можно через [состояние приложения](http://127.0.0.1:8080/health).

Для VM открой `http://VM_IP:8080/`, если порт доступен с компьютера. При доступе только по SSH запусти туннель с компьютера и оставь его открытым:

```sh
ssh -N -L 18080:127.0.0.1:8080 user@VM_IP
```

[Витрина через SSH-туннель](http://127.0.0.1:18080/)

### Контрольный сценарий после импорта

Проверь, что readiness возвращает `UP`, затем отправь точный артикул:

```bash
curl -fsS http://127.0.0.1:8080/v1/search \
  -H 'Content-Type: application/json' \
  -d '{"q":"EP2-06605","strategy":"standard"}'
```

```powershell
$body = @{ q = 'EP2-06605'; strategy = 'standard' } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/v1/search -Method Post -ContentType 'application/json' -Body $body
```

На фиде от 25 ноября 2026 года ожидается `primary_target.kind=offer` и `id=473582`. Проверь переход и артикул на странице. Главный каталог считает результаты после группировки вариантов, поэтому `Найдено` и число строк `offer` в XML различаются. [Карточка Office](http://127.0.0.1:8080/?kind=offer&id=473582) · [Первая запись каталога и общий счётчик](http://127.0.0.1:8080/v1/catalog?page_size=1)

## Дополнительные режимы

### Первый смысловой поиск через FastAPI

На Linux можно выполнить гибридный импорт целиком скриптом из репозитория:

```bash
COMPOSE_PROJECT_NAME=softline bash import-catalog.sh "$FEED"
```

Скрипт запускает `model`, собирает `app`, импортирует каталог с `--embeddings` и пересоздаёт `app`. При ручном запуске, включая PowerShell, используй тот же проект. В примере PowerShell путь `$feed` задан выше:

```powershell
docker compose -p softline --profile ml up -d --build --wait model
docker compose -p softline build app
docker compose -p softline run --rm --no-deps --volume "${feed}:/catalog.xml:ro" app import --embeddings
```

После успешного импорта:

```powershell
docker compose -p softline --profile ml up -d --force-recreate --wait app
docker compose -p softline exec -T model python demo_search.py "Как вернуть документы после поломки диска?"
```

Первая сборка `model` загружает CPU-зависимости и закреплённые веса E5. Контейнер читает их из встроенного кеша с `HF_HUB_OFFLINE=1`. Для гибридного поиска `SEMANTIC_MODE=auto` требует совпадения профиля FastAPI с профилем снимка. Проверь `semantic_ready` в `/health` и `mode` в ответе на свободный поисковый запрос. `SEMANTIC_MODE=lexical` при пересоздании `app` позволяет сравнить режимы на том же снимке. `SEARCH_TASK_SEMANTICS=true` отдельно включает семантическую оценку назначения запроса. [Контракт энкодера](#внутренний-api-энкодера)

### Локальный компилятор запросов Qwen

После импорта приложение может подключить локальный OpenAI-совместимый сервер llama.cpp. Для Qwen2.5-1.5B-Instruct Q4_K_M в Bash:

```bash
export LLM_GGUF="$HOME/softline-qwen.gguf"
curl -fL --retry 2 -o "$LLM_GGUF" \
  https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf
printf '%s  %s\n' '6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e' "$LLM_GGUF" | sha256sum -c -
```

Когда контрольная сумма совпала, подключи конфигурацию:

```bash
docker compose -p softline -f compose.yaml -f compose.llm.yaml up -d --build --wait app
docker compose -p softline -f compose.yaml -f compose.llm.yaml logs --tail=50 llm
```

Добавь `--profile ml` к команде запуска, если нужен E5. Overlay направляет Spring на `http://llm:8080/v1/chat/completions` и задаёт алиас `softline`. После загрузки модели проверь запрос в чате, затем поля `program.compiler` и `llm_applied` в ответе `POST /v1/search` с `strategy=llm`. Модель вызывается по условиям запроса. При её недоступности работает поиск на правилах и Lucene.

### Разработка и контракты API

```sh
docker compose -p softline --profile dev up -d --build --wait frontend
docker compose -p softline --profile docs up -d --build api-docs
```

Для локальной разработки с Node.js 24 выполни `npm ci` и `npm run dev` в каталоге `frontend` при запущенном Spring. Vite перенаправляет запросы к `SPRING_BASE_URL`, по умолчанию `http://localhost:8080`.

| Сервис | Профиль | Адрес и назначение |
| --- | --- | --- |
| `app` | Основной | `http://127.0.0.1:8080`, React и Spring API. Публикация задаётся `APP_PORT`. |
| `frontend` | `dev` | `http://127.0.0.1:5173`, Vite с обновлением исходников и прокси на Spring. Порт задаётся `FRONTEND_PORT`. |
| `model` | `ml`, `dev` | `http://model:8000` внутри Compose, FastAPI с E5. |
| `api-docs` | `docs` | `http://127.0.0.1:8001`, интерактивные контракты. Порт задаётся `API_DOCS_PORT`. |
| `llm` | Через `compose.llm.yaml` | `http://llm:8080` внутри Compose, llama.cpp с GGUF. |

На справочном сервисе валидный публичный вызов возвращает `501 CONTRACT_ONLY`. Бизнес-обработчик работает в Spring. [Swagger поиска](http://127.0.0.1:8001/docs) · [Swagger энкодера](http://127.0.0.1:8001/model/docs)

Основные маршруты:

- `GET /v1/catalog`, `GET /v1/suggest` и `POST /v1/search` обслуживают каталог, подсказки и поиск. Чат использует `POST /v1/search` со стратегией `llm`.
- `GET /v1/entities/{kind}/{id}` отдаёт карточку или навигационную сущность. `GET /v1/entities/offer/{id}/price-comparison` возвращает подтверждённое сравнение. `POST /v1/explain` объясняет показ конкретной карточки.
- `POST /v1/events` принимает массив событий и возвращает `204` после записи. `GET /v1/analytics` отдаёт отчёт за период `[from, to)` с часовым поясом. Дополнительные фильтры — `source` (`demo` или `seed`) и `index_version`. [OpenAPI витрины](contracts/search.openapi.yaml)

Для подсказки передай `q` и при необходимости `category_id` и `profile_id`. Выбор подсказки передаётся в `POST /v1/search` как `selected_target` с полями `kind` и `target_id`. В каталоге `page_size` принимает значения от 1 до 48, витрина запрашивает 20. Сортировка использует `relevance`, `price_asc` и `price_desc`. Идентификаторы в JSON — строки. Суммы с суффиксом `_minor` задаются в минорных единицах валюты.

Ошибки используют `application/problem+json` с полем `code`. Например, `400 UNKNOWN_TARGET` означает, что выбранная подсказка отсутствует в активном снимке, `404 CARD_NOT_FOUND` — что журнал не содержит данных показанной карточки, `503 CATALOG_UNAVAILABLE` — что снимок недоступен. [Поля событий и правила атрибуции](RESEARCH.md#журнал-и-аналитика)

### Внутренний API энкодера

Внутри Compose у `model:8000` маршрут `GET /health` проверяет HTTP-процесс, а `GET /ready` после прогрева возвращает `profile_id` и размерность. `POST /internal/v1/embeddings` принимает `profile_id`, `input_type` и `texts`. Для `query` допустим один текст, для `passage` — от 1 до 32. Сервис добавляет нужный префикс и возвращает нормализованные векторы в исходном порядке. Ошибки `409 PROFILE_MISMATCH`, `422 INVALID_REQUEST` и `503` означают несовместимый профиль, ошибку формата и недоступность модели соответственно. [OpenAPI энкодера](contracts/model.openapi.yaml)

Для локальной разработки на Python 3.12 с uv из каталога `model`:

```sh
uv run --locked --group ml uvicorn app:app --host 127.0.0.1 --port 8002
```

Первый запуск заполняет кеш весов. Готовность и тело запроса можно проверить в [Swagger рабочего FastAPI](http://127.0.0.1:8002/docs). Из каталога `model` выполняются экспорт и сверка схем, а также тесты опубликованных примеров:

```sh
uv run --locked --group docs python export_openapi.py
uv run --locked --group docs python export_openapi.py --check
uv run --locked --group docs --group test python -m unittest discover -s tests -p 'test_contract_examples.py'
```

## Проверка, остановка и диагностика

На демостенде запусти следующую проверку из корня репозитория. Она сверяет 10 запросов, сортировку, фильтры, пагинацию, пустую выдачу, ответ чата и смешанную нагрузку на локальное API.

```sh
python backend/acceptance/storefront_smoke.py http://127.0.0.1:8080 --requests 240 --workers 12
```

Для более длинного прогона используй `--requests 360 --workers 16`.

Для полного фида используй тот же XML, что и при импорте. Из корня репозитория можно оценить поиск на задачах разработки и на отложенных запросах, а также повторить проверку чата с `--strategy llm`:

```bash
python backend/acceptance/catalog_tasks.py "$FEED" --url http://127.0.0.1:8080 --task-goals development --csv goals-development.csv
python backend/acceptance/catalog_tasks.py "$FEED" --url http://127.0.0.1:8080 --task-goals holdout --repeat 3 --csv goals-holdout.csv
```

Для приёмки Java-обработчика сначала сгенерируй четыре компактных XML:

```sh
python backend/acceptance/build_fixtures.py backend/src/main/resources/search/demo.xml search-fixtures
```

Для каждого файла запусти отдельный Compose-проект по инструкции импорта выше. Из каталога `backend` выполни проверку с его портом и именем сценария без расширения. Для PowerShell используй `.\mvnw.cmd`.

```sh
./mvnw -Psearch-acceptance -Dsearch.baseUrl=http://127.0.0.1:8080 -Dsearch.scenario=baseline verify
```

Сценарии `baseline`, `missing-code`, `priced-reserve` и `unpriced-reserve` проверяют наличие, резервные предложения, ограничения и совместимость. [Методика и результаты](RESEARCH.md#проверки-и-измерения)

```sh
docker compose -p softline ps
docker compose -p softline logs --tail=100 app
docker compose -p softline --profile dev --profile docs down
```

Для демостенда используй `-p softline-demo`. Если запускал LLM, выполни остановку с теми же файлами `-f` и заданным `LLM_GGUF`. Обычный `down` сохраняет именованные тома. Флаг `-v` удаляет локальные снимки и журнал.

| Симптом | Что проверить |
| --- | --- |
| Найдено 30 карточек | На открытом порту работает демоснимок. Сверь URL, `docker compose -p softline ps`, имя проекта при импорте и `index_version` в `/health`. После импорта пересоздай `app` того же проекта. |
| Порт уже занят | Задай `APP_PORT=8182` в Bash или `$env:APP_PORT='8182'` в PowerShell перед пересозданием `app`. Обнови порт в браузере, проверках и SSH-туннеле. |
| Контейнер работает, readiness не готов | Открой журналы, проверь завершение импорта и права на тома `snapshots` и `events`. Readiness требует открытого снимка и доступного для записи журнала. |
| Блока сравнения цен нет | Сверь наличие артикула, покрытие адаптеров, валюту, дату снимка и доступ контейнера к официальному источнику. Ответ `204` означает, что для товара сейчас нет подтверждённой цены для сравнения. [Условия сравнения](RESEARCH.md#сравнение-цен) |

За Nginx на хосте можно опубликовать Spring только на loopback через `APP_PORT=127.0.0.1:8080` и указать этот адрес как upstream. Для Nginx в сети Compose upstream — `app:8080`. TLS и домен обслуживает сам прокси.

## Материалы проекта

- [RESEARCH.md](RESEARCH.md) — архитектура, реестр функций, пайплайны, ограничения и проверка.
- [Контракты OpenAPI и схемы SQLite](contracts/) — машиночитаемые форматы API и данных.
- [Материалы организатора](supplementary/) — ТЗ, ответы на вопросы и эталонные запросы.
