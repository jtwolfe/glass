{{/*
Expand the name of the chart.
*/}}
{{- define "glass.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "glass.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "glass.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "glass.labels" -}}
helm.sh/chart: {{ include "glass.chart" . }}
{{ include "glass.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "glass.selectorLabels" -}}
app.kubernetes.io/name: {{ include "glass.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
ntfy labels
*/}}
{{- define "glass.ntfy.labels" -}}
{{ include "glass.labels" . }}
app.kubernetes.io/component: ntfy
{{- end }}

{{/*
ntfy selector labels
*/}}
{{- define "glass.ntfy.selectorLabels" -}}
{{ include "glass.selectorLabels" . }}
app.kubernetes.io/component: ntfy
{{- end }}

{{/*
peer labels
*/}}
{{- define "glass.peer.labels" -}}
{{ include "glass.labels" . }}
app.kubernetes.io/component: peer
{{- end }}

{{/*
peer selector labels
*/}}
{{- define "glass.peer.selectorLabels" -}}
{{ include "glass.selectorLabels" . }}
app.kubernetes.io/component: peer
{{- end }}

{{/*
Internal ntfy URL (for peer to use)
*/}}
{{- define "glass.ntfy.internalUrl" -}}
http://{{ include "glass.fullname" . }}-ntfy.{{ .Release.Namespace }}.svc.cluster.local:{{ .Values.service.ntfyPort }}
{{- end }}

{{/*
Public ntfy URL (for phone to use)
*/}}
{{- define "glass.ntfy.publicUrl" -}}
{{- if .Values.ingress.tls.enabled -}}
https://{{ .Values.ingress.host }}{{ .Values.ingress.ntfyPathPrefix }}
{{- else -}}
http://{{ .Values.ingress.host }}{{ .Values.ingress.ntfyPathPrefix }}
{{- end }}
{{- end }}
