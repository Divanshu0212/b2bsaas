{{/* Chart name, overridable via .Values.nameOverride. */}}
{{- define "salespipe.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified release-scoped name. */}}
{{- define "salespipe.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "salespipe.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Selector labels — stable across upgrades, so never add version/checksum here. */}}
{{- define "salespipe.selectorLabels" -}}
app.kubernetes.io/name: {{ include "salespipe.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Full label set for metadata. */}}
{{- define "salespipe.labels" -}}
{{ include "salespipe.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}
