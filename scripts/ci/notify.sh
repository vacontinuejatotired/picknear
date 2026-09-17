#!/usr/bin/env bash
set -euo pipefail

provider="${NOTIFY_PROVIDER:-}"

# Preserve the old DingTalk secret name and default to the recommended provider
# when no explicit provider is configured.
if [[ -z "$provider" ]]; then
  if [[ -n "${FEISHU_WEBHOOK:-}" ]]; then
    provider="feishu"
  elif [[ -n "${DINGTALK_WEBHOOK:-}" || -n "${NOTIFY_WEBHOOK:-}" ]]; then
    provider="dingtalk"
  else
    provider="feishu"
  fi
fi

ci_result="${CI_RESULT:-}"
build_result="${BUILD_RESULT:-}"
branch="${BRANCH:-unknown}"
short_sha="${SHORT_SHA:-unknown}"
run_url="${RUN_URL:-}"

case "${ci_result}:${build_result}" in
  failure:*)
    title="CI/CD 失败"
    detail="CI 阶段失败，镜像未构建"
    ;;
  *:failure)
    title="CI/CD 失败"
    detail="镜像构建失败"
    ;;
  *:success)
    title="CI/CD 成功"
    detail="镜像构建成功，已推送 latest + sha-${short_sha}"
    ;;
  *)
    echo "Nothing to notify (ci=${ci_result}, build=${build_result})"
    exit 0
    ;;
esac

message="${detail}
分支: ${branch}
Commit: ${short_sha}"
if [[ -n "$run_url" ]]; then
  message="${message}
流水线: ${run_url}"
fi

send_feishu() {
  local webhook="${FEISHU_WEBHOOK:-}"
  if [[ -z "$webhook" ]]; then
    echo "FEISHU_WEBHOOK not set, skipping notification"
    return 0
  fi

  local timestamp string_to_sign sign payload response code
  if [[ -n "${FEISHU_SIGN_SECRET:-}" ]]; then
    timestamp="$(date +%s)"
    string_to_sign="${timestamp}"$'\n'"${FEISHU_SIGN_SECRET}"
    sign="$(printf '' | openssl dgst -sha256 -hmac "$string_to_sign" -binary | base64 | tr -d '\n')"
    payload="$(jq -n \
      --arg timestamp "$timestamp" \
      --arg sign "$sign" \
      --arg title "$title" \
      --arg message "$message" \
      '{timestamp: $timestamp, sign: $sign, msg_type: "text", content: {text: ($title + "\n" + $message)}}')"
  else
    payload="$(jq -n \
      --arg title "$title" \
      --arg message "$message" \
      '{msg_type: "text", content: {text: ($title + "\n" + $message)}}')"
  fi

  if ! response="$(curl -sS --fail-with-body \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$payload" \
    "$webhook")"; then
    echo "Feishu notification request failed"
    return 1
  fi

  echo "$response"
  code="$(jq -r '.code // 0' <<< "$response")"
  if [[ "$code" != "0" ]]; then
    echo "Feishu notification rejected (code=${code})"
    return 1
  fi
}

send_dingtalk() {
  local webhook="${DINGTALK_WEBHOOK:-${NOTIFY_WEBHOOK:-}}"
  if [[ -z "$webhook" ]]; then
    echo "DINGTALK_WEBHOOK/NOTIFY_WEBHOOK not set, skipping notification"
    return 0
  fi

  local url="$webhook"
  if [[ -n "${DINGTALK_SIGN_SECRET:-}" ]]; then
    local timestamp string_to_sign sign encoded_sign
    timestamp="$(date +%s%3N)"
    string_to_sign="${timestamp}"$'\n'"${DINGTALK_SIGN_SECRET}"
    sign="$(printf '%s' "$string_to_sign" | openssl dgst -sha256 -hmac "$DINGTALK_SIGN_SECRET" -binary | base64 | tr -d '\n')"
    encoded_sign="$(jq -rn --arg value "$sign" '$value | @uri')"
    url="${webhook}&timestamp=${timestamp}&sign=${encoded_sign}"
  fi

  local payload response errcode
  payload="$(jq -n \
    --arg title "$title" \
    --arg message "$message" \
    '{msgtype: "markdown", markdown: {title: $title, text: ("### " + $title + "\n\n" + $message)}}')"

  if ! response="$(curl -sS --fail-with-body \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$payload" \
    "$url")"; then
    echo "DingTalk notification request failed"
    return 1
  fi

  echo "$response"
  errcode="$(jq -r '.errcode // 0' <<< "$response")"
  if [[ "$errcode" != "0" ]]; then
    echo "DingTalk notification rejected (errcode=${errcode})"
    return 1
  fi
}

case "$provider" in
  feishu)
    send_feishu
    ;;
  dingtalk)
    send_dingtalk
    ;;
  *)
    echo "Unsupported NOTIFY_PROVIDER: $provider"
    exit 1
    ;;
esac

echo "Notification sent: $title"
