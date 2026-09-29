{{/*
=============================================================================
  Horizon — общие определения шаблонов
=============================================================================
  Здесь собрано всё, что иначе пришлось бы копировать в семь мест: имена,
  метки, селекторы, образы, окружение и контекст безопасности.

  Соглашение по вызовам: помощники, которым нужен и корень, и сервис,
  принимают словарь `(dict "root" $ "name" $name "svc" $svc)`. Внутри range
  точка (`.`) указывает на элемент коллекции, поэтому корень передаётся явно —
  без этого шаблоны молча получают пустые значения.
=============================================================================
*/}}

{{/* Базовое имя чарта. */}}
{{- define "horizon.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
  Префикс всех ресурсов релиза.
  Если имя релиза уже содержит имя чарта (`helm install horizon ./horizon`),
  не дублируем: `horizon-horizon-gateway` читается плохо и съедает лимит в 63 символа.
*/}}
{{- define "horizon.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Имя конкретной рабочей нагрузки: <префикс релиза>-<имя сервиса>. */}}
{{- define "horizon.workloadName" -}}
{{- printf "%s-%s" (include "horizon.fullname" .root) .name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "horizon.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
  Слияние умолчаний и настроек сервиса.
  deepCopy обязателен: mergeOverwrite изменяет приёмник на месте, и без копии
  первый же сервис в цикле испортил бы .Values.defaults для всех последующих.
*/}}
{{- define "horizon.svc" -}}
{{- mergeOverwrite (deepCopy .root.Values.defaults) (deepCopy .svc) | toYaml -}}
{{- end -}}

{{/*
  Метки. Разделены намеренно:
    * selectorLabels попадают в .spec.selector Deployment — они НЕИЗМЕНЯЕМЫ
      после создания объекта, поэтому в них нет ничего, что меняется от релиза
      к релизу (версии, чарта, окружения);
    * commonLabels включают всё остальное и обновляются свободно.
  Смешать их — значит однажды получить «field is immutable» на деплое.
*/}}
{{- define "horizon.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{- define "horizon.commonLabels" -}}
{{ include "horizon.selectorLabels" . }}
helm.sh/chart: {{ include "horizon.chart" .root }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/component: {{ .name }}
horizon.dev/environment: {{ .root.Values.global.environment }}
{{- with .root.Values.global.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{/* Полная ссылка на образ. */}}
{{- define "horizon.image" -}}
{{- $registry := .root.Values.global.imageRegistry -}}
{{- $repo := .svc.image.repository -}}
{{- $tag := default .root.Chart.AppVersion (default .root.Values.global.imageTag .svc.image.tag) -}}
{{- if .svc.image.digest -}}
{{- printf "%s/%s@%s" $registry $repo .svc.image.digest -}}
{{- else -}}
{{- printf "%s/%s:%s" $registry $repo $tag -}}
{{- end -}}
{{- end -}}

{{- define "horizon.serviceAccountName" -}}
{{- if .svc.serviceAccount.create -}}
{{- include "horizon.workloadName" . -}}
{{- else -}}
{{- default "default" .svc.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Имя сервиса для телеметрии: у ролей analytics оно общее (ADR-0014). */}}
{{- define "horizon.otelServiceName" -}}
{{- default .name .svc.serviceName -}}
{{- end -}}

{{/*
  Переменные окружения OpenTelemetry (NFR-O1).
  Заданы у КАЖДОЙ рабочей нагрузки: сервис без service.name превращается
  в безымянные спаны, по которым нельзя построить ни граф, ни SLI.
*/}}
{{- define "horizon.otelEnv" -}}
{{- $root := .root -}}
{{- $svc := .svc -}}
{{- if $root.Values.observability.otel.enabled }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ $root.Values.observability.otel.endpoint | quote }}
- name: OTEL_EXPORTER_OTLP_PROTOCOL
  value: "grpc"
- name: OTEL_SERVICE_NAME
  value: {{ include "horizon.otelServiceName" . | quote }}
{{- /* Строка собирается printf, а не свёрнутым YAML-скаляром: формат
       OTEL_RESOURCE_ATTRIBUTES — W3C Baggage, и лишние пробелы после запятых,
       которые вставил бы `>-`, часть SDK не обрезает.
       $(POD_NAME) разворачивает kubelet — переменная объявлена выше
       в horizon.commonEnv, а Kubernetes подставляет только то,
       что определено РАНЬШЕ в том же списке env. */}}
{{- $attrs := printf "service.name=%s,service.namespace=horizon,service.version=%s,service.instance.id=$(POD_NAME),deployment.environment=%s" (include "horizon.otelServiceName" .) (default $root.Chart.AppVersion $root.Values.global.imageTag) $root.Values.global.environment -}}
{{- if $svc.role }}
{{- $attrs = printf "%s,service.role=%s" $attrs $svc.role -}}
{{- end }}
- name: OTEL_RESOURCE_ATTRIBUTES
  value: {{ $attrs | quote }}
- name: OTEL_TRACES_SAMPLER
  value: "parentbased_traceidratio"
- name: OTEL_TRACES_SAMPLER_ARG
  value: {{ $root.Values.observability.otel.samplingRatio | quote }}
{{- /* Метрики собирает Prometheus скрейпом, логи — агент узла.
       Дублировать их через OTLP значит платить дважды за одни данные. */}}
- name: OTEL_METRICS_EXPORTER
  value: "none"
- name: OTEL_LOGS_EXPORTER
  value: "none"
{{- /* Spring Boot экспортирует трейсы своим OTLP-экспортером по HTTP. */}}
- name: MANAGEMENT_OTLP_TRACING_ENDPOINT
  value: {{ printf "%s/v1/traces" $root.Values.observability.otel.httpEndpoint | quote }}
- name: MANAGEMENT_TRACING_SAMPLING_PROBABILITY
  value: {{ $root.Values.observability.otel.samplingRatio | quote }}
{{- else }}
- name: OTEL_SDK_DISABLED
  value: "true"
{{- end }}
{{- end -}}

{{/*
  Переменные, общие для всех сервисов: координаты инфраструктуры и
  идентификация пода. Пароли сюда НЕ попадают — они приходят через
  envFrom secretRef (NFR-S2).
*/}}
{{- define "horizon.commonEnv" -}}
{{- $root := .root -}}
{{- $svc := .svc -}}
- name: POD_NAME
  valueFrom:
    fieldRef:
      fieldPath: metadata.name
- name: POD_NAMESPACE
  valueFrom:
    fieldRef:
      fieldPath: metadata.namespace
- name: NODE_NAME
  valueFrom:
    fieldRef:
      fieldPath: spec.nodeName
- name: HORIZON_ENV
  value: {{ $root.Values.global.environment | quote }}
- name: HORIZON_VERSION
  value: {{ default $root.Chart.AppVersion $root.Values.global.imageTag | quote }}
- name: TZ
  value: "UTC"
{{- if $svc.schema }}
{{- /* Строка подключения без пароля: пароль приходит отдельной переменной
       из Secret. currentSchema прибивает сервис к своей схеме (ADR-0006). */}}
- name: SPRING_DATASOURCE_URL
  value: {{ printf "jdbc:postgresql://%s:%v/%s?currentSchema=%s&sslmode=%s&ApplicationName=%s"
            $root.Values.infrastructure.postgres.host
            $root.Values.infrastructure.postgres.port
            $root.Values.infrastructure.postgres.database
            $svc.schema
            $root.Values.infrastructure.postgres.sslMode
            .name | quote }}
- name: SPRING_FLYWAY_SCHEMAS
  value: {{ $svc.schema | quote }}
- name: SPRING_FLYWAY_DEFAULT_SCHEMA
  value: {{ $svc.schema | quote }}
- name: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
  value: {{ $root.Values.infrastructure.postgres.poolMaxSize | quote }}
- name: SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE
  value: {{ $root.Values.infrastructure.postgres.poolMinIdle | quote }}
{{- /* Python-роли читают ту же базу через свой URL (без JDBC-префикса). */}}
- name: HORIZON_DATABASE_HOST
  value: {{ $root.Values.infrastructure.postgres.host | quote }}
- name: HORIZON_DATABASE_PORT
  value: {{ $root.Values.infrastructure.postgres.port | quote }}
- name: HORIZON_DATABASE_NAME
  value: {{ $root.Values.infrastructure.postgres.database | quote }}
- name: HORIZON_DATABASE_SCHEMA
  value: {{ $svc.schema | quote }}
{{- end }}
- name: SPRING_KAFKA_BOOTSTRAP_SERVERS
  value: {{ $root.Values.infrastructure.kafka.bootstrapServers | quote }}
- name: HORIZON_KAFKA_BOOTSTRAP_SERVERS
  value: {{ $root.Values.infrastructure.kafka.bootstrapServers | quote }}
- name: SPRING_DATA_REDIS_HOST
  value: {{ $root.Values.infrastructure.redis.host | quote }}
- name: SPRING_DATA_REDIS_PORT
  value: {{ $root.Values.infrastructure.redis.port | quote }}
- name: HORIZON_CLICKHOUSE_URL
  value: {{ printf "http://%s:%v" $root.Values.infrastructure.clickhouse.host $root.Values.infrastructure.clickhouse.httpPort | quote }}
- name: HORIZON_CLICKHOUSE_DB
  value: {{ $root.Values.infrastructure.clickhouse.database | quote }}
{{- /* Адреса upstream для маршрутов шлюза. */}}
- name: HORIZON_UPSTREAM_TRENDS_URL
  value: {{ printf "http://%s-trends-service:8082" (include "horizon.fullname" $root) | quote }}
- name: HORIZON_UPSTREAM_INGESTION_URL
  value: {{ printf "http://%s-ingestion-service:8083" (include "horizon.fullname" $root) | quote }}
- name: HORIZON_UPSTREAM_ANALYTICS_URL
  value: {{ printf "http://%s-analytics-api:8000" (include "horizon.fullname" $root) | quote }}
- name: HORIZON_CORS_ALLOWED_ORIGINS
  value: {{ printf "https://%s" $root.Values.global.domain | quote }}
- name: HORIZON_GATEWAY_URL
  value: {{ printf "http://%s-gateway:8080" (include "horizon.fullname" $root) | quote }}
{{- end -}}

{{/* Контекст безопасности контейнера (NFR-S9). */}}
{{- define "horizon.containerSecurityContext" -}}
runAsNonRoot: {{ .svc.securityContext.runAsNonRoot }}
runAsUser: {{ .root.Values.global.runAsUser }}
runAsGroup: {{ .root.Values.global.runAsGroup }}
readOnlyRootFilesystem: {{ .svc.securityContext.readOnlyRootFilesystem }}
allowPrivilegeEscalation: {{ .svc.securityContext.allowPrivilegeEscalation }}
privileged: {{ .svc.securityContext.privileged }}
capabilities:
{{ toYaml .svc.securityContext.capabilities | indent 2 }}
seccompProfile:
{{ toYaml .svc.securityContext.seccompProfile | indent 2 }}
{{- end -}}

{{/* Контекст безопасности пода. */}}
{{- define "horizon.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ .root.Values.global.runAsUser }}
runAsGroup: {{ .root.Values.global.runAsGroup }}
fsGroup: {{ .root.Values.global.fsGroup }}
{{- /* fsGroupChangePolicy: OnRootMismatch — без него kubelet рекурсивно
       меняет владельца на каждом томе при каждом старте пода. */}}
fsGroupChangePolicy: OnRootMismatch
seccompProfile:
{{ toYaml .svc.securityContext.seccompProfile | indent 2 }}
{{- end -}}

{{/*
  Проба. Вызов: (dict "root" $ "svc" $s "probe" $s.probes.liveness "kind" $s.probes.type "port" $port)
  Единый помощник вместо трёх почти одинаковых блоков в Deployment.
*/}}
{{- define "horizon.probe" -}}
{{- if eq .kind "exec" }}
exec:
  command:
{{ toYaml .probe.command | indent 4 }}
{{- else }}
httpGet:
  path: {{ .probe.path }}
  port: {{ .port }}
  scheme: HTTP
{{- end }}
{{- with .probe.initialDelaySeconds }}
initialDelaySeconds: {{ . }}
{{- end }}
periodSeconds: {{ .probe.periodSeconds }}
timeoutSeconds: {{ .probe.timeoutSeconds }}
failureThreshold: {{ .probe.failureThreshold }}
{{- with .probe.successThreshold }}
successThreshold: {{ . }}
{{- end }}
{{- end -}}

{{/* Селектор подов сервиса — используется в NetworkPolicy. */}}
{{- define "horizon.podSelector" -}}
matchLabels:
  app.kubernetes.io/name: {{ .name }}
  app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}
