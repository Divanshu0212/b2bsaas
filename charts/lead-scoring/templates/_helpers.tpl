{{- define "lead-scoring.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "lead-scoring.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "lead-scoring.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "lead-scoring.selectorLabels" -}}
app.kubernetes.io/name: {{ include "lead-scoring.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "lead-scoring.labels" -}}
{{ include "lead-scoring.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}
