{{- define "frontend.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "frontend.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "frontend.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "frontend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "frontend.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "frontend.labels" -}}
{{ include "frontend.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}
