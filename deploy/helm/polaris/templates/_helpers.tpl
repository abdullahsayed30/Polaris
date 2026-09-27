{{/* Chart name, constrained to a DNS label. */}}
{{- define "polaris.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/* Release-qualified resource name. */}}
{{- define "polaris.fullname" -}}
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
{{- end }}

{{- define "polaris.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "polaris.labels" -}}
helm.sh/chart: {{ include "polaris.chart" . }}
app.kubernetes.io/name: {{ include "polaris.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: polaris
{{- end }}

{{- define "polaris.selectorLabels" -}}
app.kubernetes.io/name: {{ include "polaris.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .service }}
{{- end }}

{{- define "polaris.serviceName" -}}
{{- printf "%s-%s" (include "polaris.fullname" .root) .service | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "polaris.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "polaris.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end }}
