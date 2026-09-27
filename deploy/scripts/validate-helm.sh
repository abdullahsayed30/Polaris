#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
chart_dir=$(CDPATH= cd -- "${script_dir}/../helm/polaris" && pwd)
example_values="${chart_dir}/examples/production-values.yaml"
rendered_file=$(mktemp "${TMPDIR:-/tmp}/polaris-helm.XXXXXX")
rendered_example_file=$(mktemp "${TMPDIR:-/tmp}/polaris-helm-example.XXXXXX")
trap 'rm -f "${rendered_file}" "${rendered_example_file}"' EXIT HUP INT TERM

if ! command -v helm >/dev/null 2>&1; then
  echo "error: helm is required (https://helm.sh/docs/intro/install/)" >&2
  exit 127
fi

helm lint --strict "${chart_dir}"
helm lint --strict "${chart_dir}" --values "${example_values}"
helm template polaris-validation "${chart_dir}" \
  --namespace polaris-validation \
  >"${rendered_file}"
helm template polaris-example "${chart_dir}" \
  --namespace polaris-example \
  --values "${example_values}" \
  >"${rendered_example_file}"

if command -v kubeconform >/dev/null 2>&1; then
  kubeconform -strict -summary -kubernetes-version 1.27.0 <"${rendered_file}"
  kubeconform -strict -summary -kubernetes-version 1.27.0 <"${rendered_example_file}"
elif command -v kubectl >/dev/null 2>&1; then
  if ! kubectl config current-context >/dev/null 2>&1; then
    echo "warning: kubectl has no current cluster context; helm lint/template succeeded." >&2
  elif kubectl apply --dry-run=client --validate=false -f "${rendered_file}" >/dev/null \
    && kubectl apply --dry-run=client --validate=false -f "${rendered_example_file}" >/dev/null; then
    echo "kubectl dry-run completed; install kubeconform for offline schema validation."
  else
    echo "warning: kubectl dry-run needs reachable API discovery; helm lint/template succeeded." >&2
  fi
else
  echo "warning: kubeconform and kubectl are unavailable; schema validation was skipped." >&2
fi

echo "Helm chart validation completed."
