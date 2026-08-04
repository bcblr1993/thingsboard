#!/usr/bin/env bash
#
# Copyright © 2016-2025 The Thingsboard Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -euo pipefail

source /opt/iotdb-ha/bin/common.sh
validate_runtime_env

INTERVAL=${CONTROLLER_INTERVAL_SECONDS:-1}
PIPE_CHECK_INTERVAL=${PIPE_CHECK_INTERVAL_SECONDS:-15}
PIPE_STALL=${PIPE_STALL_SECONDS:-60}
STANDBY_AUDIT_INTERVAL=${STANDBY_PIPE_AUDIT_INTERVAL_SECONDS:-300}
PROGRESS_MAX_AGE=${CONTROLLER_PROGRESS_MAX_AGE_SECONDS:-5}
PIPE_BAD_CONFIRMATIONS=${PIPE_NOT_RUNNING_CONFIRMATIONS:-3}
PIPE_REPAIR_BACKOFF_MAX=${PIPE_REPAIR_BACKOFF_MAX_SECONDS:-300}
PIPE_PROGRESS_MIN_BYTES=${PIPE_PROGRESS_MIN_BYTES:-1048576}
[[ "${PIPE_STALL}" =~ ^[0-9]+$ ]] || die "invalid PIPE_STALL_SECONDS"
[[ "${STANDBY_AUDIT_INTERVAL}" =~ ^[0-9]+$ ]] || die "invalid STANDBY_PIPE_AUDIT_INTERVAL_SECONDS"
[[ "${PROGRESS_MAX_AGE}" =~ ^[0-9]+$ ]] || die "invalid CONTROLLER_PROGRESS_MAX_AGE_SECONDS"
[[ "${PIPE_BAD_CONFIRMATIONS}" =~ ^[0-9]+$ && "${PIPE_BAD_CONFIRMATIONS}" -gt 0 ]] \
  || die "invalid PIPE_NOT_RUNNING_CONFIRMATIONS"
[[ "${PIPE_REPAIR_BACKOFF_MAX}" =~ ^[0-9]+$ && "${PIPE_REPAIR_BACKOFF_MAX}" -ge 15 ]] \
  || die "invalid PIPE_REPAIR_BACKOFF_MAX_SECONDS"
[[ "${PIPE_PROGRESS_MIN_BYTES}" =~ ^[0-9]+$ && "${PIPE_PROGRESS_MIN_BYTES}" -gt 0 ]] \
  || die "invalid PIPE_PROGRESS_MIN_BYTES"
[[ "${INTERVAL}" =~ ^[0-9]+$ && "${INTERVAL}" -gt 0 && "${INTERVAL}" -lt "${PROGRESS_MAX_AGE}" ]] \
  || die "CONTROLLER_INTERVAL_SECONDS must be a positive integer below CONTROLLER_PROGRESS_MAX_AGE_SECONDS"
last_role=
last_pipe_check=0
last_standby_audit=0
history_zero_count=0
last_history_backlog=-1
last_history_progress=$(date +%s)
last_realtime_backlog=-1
last_realtime_progress=$(date +%s)
realtime_agent_state=MASTER_SYNCING
last_proxy_bytes=-1
proxy_transfer_progress=false
controller_main_pid=$$
heartbeat_pid=
active_lease_pid=
active_lease_token=
active_lease_mode=
lease_generation=0
pipe_not_running_count=0
history_not_running_count=0
pipe_repair_backoff=15
next_pipe_repair_at=0

export CONTROLLER_WATCHDOG_ACTIVE=true
rm -f "${CONTROLLER_STALLED_FILE}" "${CONTROLLER_BUSY_UNTIL_FILE}"
atomic_write "${CONTROLLER_PROGRESS_FILE}" "$(date +%s)"

controller_touch() {
  atomic_write "${CONTROLLER_PROGRESS_FILE}" "$(date +%s)"
}

schedule_pipe_repair() {
  local scheduled_now
  scheduled_now=$(date +%s)
  next_pipe_repair_at=$((scheduled_now + pipe_repair_backoff))
  log "Pipe repair deferred for ${pipe_repair_backoff}s"
  pipe_repair_backoff=$((pipe_repair_backoff * 2))
  (( pipe_repair_backoff <= PIPE_REPAIR_BACKOFF_MAX )) \
    || pipe_repair_backoff=${PIPE_REPAIR_BACKOFF_MAX}
}

reset_pipe_repair_backoff() {
  pipe_not_running_count=0
  pipe_repair_backoff=15
  next_pipe_repair_at=0
}

controller_parent_alive() {
  local state
  [[ -r "/proc/${controller_main_pid}/stat" ]] || return 1
  read -r _ _ state _ < "/proc/${controller_main_pid}/stat" || return 1
  [[ "${state}" != Z ]]
}

stop_lease_keeper() {
  local i
  if [[ -n "${active_lease_pid}" ]]; then
    kill -TERM "${active_lease_pid}" >/dev/null 2>&1 || true
    for ((i = 0; i < 30; i++)); do
      kill -0 "${active_lease_pid}" >/dev/null 2>&1 || break
      controller_touch
      sleep 0.1
    done
    kill -KILL "${active_lease_pid}" >/dev/null 2>&1 || true
    wait "${active_lease_pid}" >/dev/null 2>&1 || true
    active_lease_pid=
    active_lease_token=
    active_lease_mode=
  fi
}

transition_fenced() {
  local rc=0
  controller_touch
  /opt/iotdb-ha/bin/fence.sh fenced || rc=$?
  controller_touch
  stop_lease_keeper
  controller_touch
  if (( rc != 0 )); then
    /opt/iotdb-ha/bin/fence.sh fenced || return 1
    controller_touch
  fi
}

ensure_fenced() {
  if [[ "$(read_state "${FENCE_DESIRED_FILE}" unknown)" == fenced \
        && -z "${active_lease_pid}" ]]; then
    return 0
  fi
  transition_fenced
}

transition_client() {
  if ! start_lease_keeper client; then
    transition_fenced >/dev/null 2>&1 || true
    return 1
  fi
}

start_lease_keeper() {
  local requested_mode=$1 new_token desired
  [[ "${requested_mode}" == client || "${requested_mode}" == synced ]] || return 2

  if [[ -n "${active_lease_pid}" ]] && kill -0 "${active_lease_pid}" 2>/dev/null \
     && [[ "${active_lease_mode}" == "${requested_mode}" ]]; then
    desired=$(read_state "${FENCE_DESIRED_FILE}" fenced)
    if [[ "${desired}" == "${requested_mode}:${active_lease_token}" \
          && $(file_age_seconds "${LEASE_OK_FILE}") -le ${LEASE_HEARTBEAT_MAX_AGE_SECONDS:-6} ]]; then
      return 0
    fi
    # keeper 存活但没有按期成功续租，先撤销旧代次，不能把 PID 存活
    # 当成租约健康。
    /opt/iotdb-ha/bin/fence.sh fenced >/dev/null 2>&1 || true
    stop_lease_keeper
  fi

  lease_generation=$((lease_generation + 1))
  new_token="${NODE_ID}.${controller_main_pid}.${lease_generation}.$(date +%s)"

  # 新代次先在锁内取代旧 desired token，再停止旧 keeper；迟到的旧
  # renew/revoke 因 token 不匹配只能失败，不能重开或误关新租约。
  controller_touch
  /opt/iotdb-ha/bin/fence.sh "authorize-${requested_mode}" "${new_token}" || return 1
  controller_touch
  stop_lease_keeper
  controller_touch
  active_lease_token=${new_token}
  active_lease_mode=${requested_mode}
  (
    trap - EXIT TERM INT QUIT
    while controller_parent_alive && [[ ! -e "${CONTROLLER_STALLED_FILE}" ]]; do
      /opt/iotdb-ha/bin/fence.sh "renew-${requested_mode}" "${new_token}" || break
      sleep 1
    done
    /opt/iotdb-ha/bin/fence.sh "revoke-${requested_mode}" "${new_token}" >/dev/null 2>&1 || true
  ) &
  active_lease_pid=$!
}

cleanup_controller() {
  trap - EXIT TERM INT QUIT
  transition_fenced >/dev/null 2>&1 || true
  [[ -n "${heartbeat_pid}" ]] && kill -TERM "${heartbeat_pid}" >/dev/null 2>&1 || true
  [[ -n "${heartbeat_pid}" ]] && wait "${heartbeat_pid}" >/dev/null 2>&1 || true
}

trap 'cleanup_controller; exit 143' TERM INT QUIT
trap cleanup_controller EXIT

(
  trap - EXIT TERM INT QUIT
  while controller_parent_alive; do
    now=$(date +%s)
    progress_age=$(file_age_seconds "${CONTROLLER_PROGRESS_FILE}")
    busy_until=$(read_state "${CONTROLLER_BUSY_UNTIL_FILE}" 0)
    [[ "${busy_until}" =~ ^[0-9]+$ ]] || busy_until=0
    if (( progress_age <= PROGRESS_MAX_AGE || busy_until >= now )); then
      atomic_write "${CONTROLLER_OK_FILE}" "${now}"
    else
      atomic_write "${CONTROLLER_STALLED_FILE}" "progress_age=${progress_age}"
      /opt/iotdb-ha/bin/fence.sh fenced >/dev/null 2>&1 || true
      # 不能依赖健康检查让 Docker 自动重启（restart policy 不处理
      # unhealthy）。先 fail-closed，再终止主状态机，让入口进程发现关键
      # 子进程退出并完成整容器重启。
      kill -KILL "${controller_main_pid}" >/dev/null 2>&1 || true
      break
    fi
    sleep 1
  done
) &
heartbeat_pid=$!

set_agent() {
  local value=$1 current
  current=$(read_state "${AGENT_STATE_FILE}" UNKNOWN)
  [[ "${current}" == "${value}" ]] || {
    atomic_write "${AGENT_STATE_FILE}" "${value}"
    log "agent state: ${current} -> ${value}"
  }
}

reset_realtime_health() {
  pipe_not_running_count=0
  history_not_running_count=0
  last_history_backlog=-1
  last_history_progress=$(date +%s)
  last_realtime_backlog=-1
  last_realtime_progress=$(date +%s)
  realtime_agent_state=MASTER_SYNCING
  last_proxy_bytes=-1
  proxy_transfer_progress=false
}

refresh_proxy_transfer_progress() {
  local current
  proxy_transfer_progress=false
  current=$(read_state "${PROXY_BYTES_FILE}" UNKNOWN)
  if [[ "${current}" =~ ^[0-9]+$ ]]; then
    if (( last_proxy_bytes >= 0 && current >= last_proxy_bytes \
          && current - last_proxy_bytes >= PIPE_PROGRESS_MIN_BYTES )); then
      proxy_transfer_progress=true
    fi
    last_proxy_bytes=${current}
  fi
}

evaluate_history_health() {
  local remaining check_now
  check_now=$(date +%s)
  remaining=$(pipe_remaining_event_count "$(pipe_history_id)" 2>/dev/null || true)

  if [[ ! "${remaining}" =~ ^[0-9]+$ ]]; then
    history_zero_count=0
    if (( check_now - last_history_progress >= PIPE_STALL )); then
      set_agent MASTER_SYNC_DEGRADED_HISTORY_STATUS
    else
      set_agent MASTER_SYNCING
    fi
    return 0
  fi

  if (( remaining == 0 )); then
    history_zero_count=$((history_zero_count + 1))
    last_history_progress=${check_now}
    set_agent MASTER_SYNCING
  else
    history_zero_count=0
    if (( last_history_backlog < 0 || remaining < last_history_backlog )) \
       || [[ "${proxy_transfer_progress}" == true ]]; then
      last_history_progress=${check_now}
    fi
    if (( check_now - last_history_progress >= PIPE_STALL )); then
      set_agent MASTER_SYNC_DEGRADED_HISTORY_BACKLOG
    else
      set_agent MASTER_SYNCING
    fi
  fi
  last_history_backlog=${remaining}
}

evaluate_realtime_health() {
  local remaining check_now
  check_now=$(date +%s)

  if ! proxy_is_healthy; then
    realtime_agent_state=MASTER_SYNC_DEGRADED_PROXY
    return 0
  fi

  remaining=$(pipe_remaining_event_count "$(pipe_realtime_id)" 2>/dev/null || true)
  if [[ ! "${remaining}" =~ ^[0-9]+$ ]]; then
    if (( check_now - last_realtime_progress >= PIPE_STALL )); then
      realtime_agent_state=MASTER_SYNC_DEGRADED_STATUS
    else
      realtime_agent_state=MASTER_SYNCING
    fi
    return 0
  fi

  if (( remaining == 0 )); then
    last_realtime_progress=${check_now}
    realtime_agent_state=MASTER_SYNCED
  else
    if (( last_realtime_backlog < 0 || remaining < last_realtime_backlog )) \
       || [[ "${proxy_transfer_progress}" == true ]]; then
      last_realtime_progress=${check_now}
    fi
    if (( check_now - last_realtime_progress >= PIPE_STALL )); then
      realtime_agent_state=MASTER_SYNC_DEGRADED_BACKLOG
    else
      realtime_agent_state=MASTER_SYNCING
    fi
  fi
  last_realtime_backlog=${remaining}
}

drop_local_direction() {
  transition_fenced || {
    atomic_write "${PIPE_STATE_FILE}" ERROR
    return 1
  }
  if drop_outgoing_pipes; then
    atomic_write "${PIPE_STATE_FILE}" NONE
    return 0
  fi
  atomic_write "${PIPE_STATE_FILE}" ERROR
  return 1
}

create_current_direction() {
  local rc=0 proxy_ready=false _

  # CREATE/SHOW 可能持续数秒，由受父进程和 VRRP 角色约束的子进程续租。
  start_lease_keeper synced || {
    transition_client >/dev/null 2>&1 || true
    atomic_write "${PIPE_STATE_FILE}" ERROR
    return 1
  }
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if nc -z -w 1 "${PIPE_PROXY_HOST}" "${PIPE_PROXY_PORT}" >/dev/null 2>&1; then
      proxy_ready=true
      break
    fi
    controller_touch
    sleep 0.2
  done
  if [[ "${proxy_ready}" != true ]]; then
    log "Pipe proxy listener did not become ready within the bounded startup window"
    transition_client >/dev/null 2>&1 || true
    atomic_write "${PIPE_STATE_FILE}" ERROR
    return 1
  fi
  create_full_pipe || rc=$?

  if (( rc == 0 )); then
    atomic_write "${PIPE_STATE_FILE}" FULL_SYNC
    return 0
  fi

  transition_client >/dev/null 2>&1 || true
  atomic_write "${PIPE_STATE_FILE}" ERROR
  return 1
}

while true; do
  [[ ! -e "${CONTROLLER_STALLED_FILE}" ]] || die "controller watchdog declared the main loop stalled"
  controller_touch
  role=$(read_state "${VRRP_STATE_FILE}" STARTING)
  vip_present=false
  local_vip_present && vip_present=true
  if [[ "${vip_present}" == true && "${role}" != MASTER ]] \
     || [[ "${vip_present}" == false && "${role}" == MASTER ]]; then
    if ! /opt/iotdb-ha/bin/fence.sh reconcile-role; then
      transition_fenced >/dev/null 2>&1 || true
      set_agent FENCED_PIPE_ERROR
      sleep "${INTERVAL}"
      continue
    fi
    role=$(read_state "${VRRP_STATE_FILE}" STARTING)
  fi
  now=$(date +%s)
  periodic=false
  if (( now - last_pipe_check >= PIPE_CHECK_INTERVAL )); then
    periodic=true
    last_pipe_check=${now}
  fi

  if [[ "${role}" != MASTER ]]; then
    if ! ensure_fenced; then
      set_agent FENCED_PIPE_ERROR
      sleep "${INTERVAL}"
      continue
    fi
    audit_due=false
    current_agent_state=$(read_state "${AGENT_STATE_FILE}" UNKNOWN)
    if [[ "${current_agent_state}" == FENCED_PIPE_ERROR ]] \
       && (( now - last_standby_audit >= PIPE_CHECK_INTERVAL )); then
      # 上一次清理/审计失败时按较短周期重试，不能等常规 5 分钟审计。
      audit_due=true
      last_standby_audit=${now}
    elif (( now - last_standby_audit >= STANDBY_AUDIT_INTERVAL )); then
      audit_due=true
      last_standby_audit=${now}
    fi

    if [[ "${role}" != "${last_role}" ]]; then
      # Keepalived 启动时会把 STARTING 通知为 BACKUP；若启动门禁已经完成
      # stale Pipe 清理，不要再次启动 3 次 CLI DROP，避免无意义资源尖峰。
      if [[ "${current_agent_state}" == STANDBY_READY \
            && "$(read_state "${FENCE_DESIRED_FILE}" unknown)" == fenced ]]; then
        set_agent STANDBY_READY
      elif drop_local_direction; then
        set_agent STANDBY_READY
      else
        set_agent FENCED_PIPE_ERROR
      fi
      last_standby_audit=${now}
    elif [[ "${audit_due}" == true ]]; then
      if outgoing_pipe_exists; then
        if drop_local_direction; then
          set_agent STANDBY_READY
        else
          set_agent FENCED_PIPE_ERROR
        fi
      else
        audit_rc=$?
        if (( audit_rc > 1 )); then
          set_agent FENCED_PIPE_ERROR
        else
          set_agent STANDBY_READY
        fi
      fi
    fi
    history_zero_count=0
    reset_realtime_health
    rm -f "${FORCE_FAULT_FILE}"
    last_role=${role}
    sleep "${INTERVAL}"
    continue
  fi

  if ! local_vip_present; then
    transition_fenced >/dev/null 2>&1 || true
    set_agent MASTER_WAITING_VIP
    last_role=${role}
    sleep "${INTERVAL}"
    continue
  fi

  line=$(peer_status || true)
  peer_agent=$(status_value "${line}" agent 2>/dev/null || true)
  peer_sql=$(status_value "${line}" sql 2>/dev/null || true)
  peer_vip=$(status_value "${line}" vip 2>/dev/null || true)
  peer_priority=$(status_value "${line}" priority 2>/dev/null || printf '0')

  # Keepalived 的角色回调异步，冲突判据必须使用对端内核实际持有 VIP，
  # 不能使用可能迟到的 vrrp.state 文本。
  if [[ "${peer_vip}" == YES ]]; then
    transition_fenced >/dev/null 2>&1 || true
    if [[ "${peer_priority}" =~ ^[0-9]+$ ]] && (( peer_priority > VRRP_PRIORITY )); then
      set_agent CONFLICT_YIELD
      # 先让 Keepalived 立即摘除低优先级 VIP；Pipe 出口已经 fenced，
      # 不得让 3 次 DROP + SHOW 的慢 CLI 把让位阻塞几十秒。转入
      # BACKUP 后再由常规 standby 清理路径处理持久 Pipe。
      touch "${FORCE_FAULT_FILE}"
    else
      set_agent CONFLICT_WAIT
    fi
    last_role=${role}
    sleep "${INTERVAL}"
    continue
  fi

  if [[ -z "${line}" || "${peer_agent}" != STANDBY_READY || "${peer_sql}" != UP ]]; then
    # 对端不可达时不创建 Pipe，先作为 MASTER_SOLO 自动接管并在本机持久化。
    # 旧主恢复、清理旧方向并明确报告 STANDBY_READY 后再进行全量补齐。
    rm -f "${FORCE_FAULT_FILE}"
    if ! transition_client; then
      set_agent MASTER_PIPE_ERROR
      last_role=${role}
      sleep "${INTERVAL}"
      continue
    fi
    set_agent MASTER_SOLO
    current_pipe_state=$(read_state "${PIPE_STATE_FILE}" NONE)
    case "${current_pipe_state}" in
      FULL_SYNC|QUEUED_FULL) atomic_write "${PIPE_STATE_FILE}" QUEUED_FULL ;;
      REALTIME|QUEUED_REALTIME) atomic_write "${PIPE_STATE_FILE}" QUEUED_REALTIME ;;
      *) atomic_write "${PIPE_STATE_FILE}" NONE ;;
    esac
    history_zero_count=0
    reset_realtime_health
    last_role=${role}
    sleep "${INTERVAL}"
    continue
  fi

  rm -f "${FORCE_FAULT_FILE}"

  if ! proxy_is_healthy; then
    # VIP 继续服务，避免切到可能落后的备机；但 Pipe 代理租约保持关闭，
    # 并明确暴露 DEGRADED 状态，代理恢复后下一轮自动续传。
    transition_client >/dev/null 2>&1 || true
    set_agent MASTER_SYNC_DEGRADED_PROXY
    realtime_agent_state=MASTER_SYNC_DEGRADED_PROXY
    pipe_not_running_count=0
    history_not_running_count=0
    last_role=${role}
    sleep "${INTERVAL}"
    continue
  fi

  [[ "${periodic}" == true ]] && refresh_proxy_transfer_progress

  current_pipe_state=$(read_state "${PIPE_STATE_FILE}" NONE)
  need_create=false
  if [[ "${current_pipe_state}" == NONE ]]; then
    need_create=true
  elif [[ "${current_pipe_state}" == ERROR ]]; then
    if (( now >= next_pipe_repair_at )); then
      need_create=true
    else
      transition_client >/dev/null 2>&1 || true
      set_agent MASTER_PIPE_ERROR
      last_role=${role}
      sleep "${INTERVAL}"
      continue
    fi
  elif [[ "${periodic}" == true ]]; then
    if realtime_pipe_running; then
      pipe_check_rc=0
    else
      pipe_check_rc=$?
    fi
    case "${pipe_check_rc}" in
      0)
        pipe_not_running_count=0
        ;;
      1)
        pipe_not_running_count=$((pipe_not_running_count + 1))
        history_not_running_count=0
        set_agent MASTER_SYNC_DEGRADED_PIPE_NOT_RUNNING
        realtime_agent_state=MASTER_SYNC_DEGRADED_PIPE_NOT_RUNNING
        if (( pipe_not_running_count >= PIPE_BAD_CONFIRMATIONS \
              && now >= next_pipe_repair_at )); then
          need_create=true
        else
          last_role=${role}
          sleep "${INTERVAL}"
          continue
        fi
        ;;
      *)
        # SHOW PIPE 查询错误与“确认未运行”严格分离。现场负载高时一次
        # CLI 抖动绝不能触发 DROP/CREATE 全量同步风暴。
        set_agent MASTER_SYNC_DEGRADED_STATUS
        realtime_agent_state=MASTER_SYNC_DEGRADED_STATUS
        pipe_not_running_count=0
        history_not_running_count=0
        last_role=${role}
        sleep "${INTERVAL}"
        continue
        ;;
    esac
  fi

  if [[ "${need_create}" == true ]]; then
    if ! transition_client; then
      atomic_write "${PIPE_STATE_FILE}" ERROR
      set_agent MASTER_PIPE_ERROR
      last_role=${role}
      sleep "${INTERVAL}"
      continue
    fi
    set_agent MASTER_CREATING_PIPE
    # 清理一次失败创建可能留下的半成品，再创建 history + realtime 同方向任务。
    if ! drop_outgoing_pipes; then
      atomic_write "${PIPE_STATE_FILE}" ERROR
      set_agent MASTER_PIPE_ERROR
      schedule_pipe_repair
      last_role=${role}
      sleep "${INTERVAL}"
      continue
    fi
    if ! create_current_direction; then
      set_agent MASTER_PIPE_ERROR
      schedule_pipe_repair
      last_role=${role}
      sleep "${INTERVAL}"
      continue
    fi
    set_agent MASTER_SYNCING
    history_zero_count=0
    reset_realtime_health
    reset_pipe_repair_backoff
  fi

  if ! start_lease_keeper synced; then
    transition_client >/dev/null 2>&1 || true
    atomic_write "${PIPE_STATE_FILE}" ERROR
    set_agent MASTER_PIPE_ERROR
    schedule_pipe_repair
    last_role=${role}
    sleep "${INTERVAL}"
    continue
  fi

  current_pipe_state=$(read_state "${PIPE_STATE_FILE}" FULL_SYNC)
  if [[ "${periodic}" != true ]]; then
    current_agent_state=$(read_state "${AGENT_STATE_FILE}" UNKNOWN)
    case "${current_agent_state}" in
      MASTER_SYNC_DEGRADED_*|MASTER_PIPE_ERROR)
        # DEGRADED 必须持续暴露到下一次真实周期检查成功，不能下一秒被
        # MASTER_SYNCING 覆盖。
        ;;
      *)
        if [[ "${current_pipe_state}" == REALTIME ]]; then
          set_agent "${realtime_agent_state}"
        else
          set_agent MASTER_SYNCING
        fi
        ;;
    esac
  else
    if history_pipe_running; then
      history_check_rc=0
    else
      history_check_rc=$?
    fi

    if (( history_check_rc == 0 )); then
      history_not_running_count=0
      evaluate_history_health

      # 连续三次检查历史 backlog 为 0 后删除 history，只保留限速 realtime。
      if (( history_zero_count >= 3 )); then
        if drop_pipe_id "$(pipe_history_id)"; then
          # DROP 已确认成功后先持久化阶段，后续 realtime SHOW 即使瞬时
          # 查询失败也只报 DEGRADED，不回滚成全量重建。
          atomic_write "${PIPE_STATE_FILE}" REALTIME
          reset_realtime_health
          set_agent MASTER_SYNCING
          history_zero_count=0
          log "history catch-up completed; dropped $(pipe_history_id), realtime direction remains"
        else
          set_agent MASTER_SYNC_DEGRADED_STATUS
        fi
      fi
    elif (( history_check_rc > 1 )); then
      history_not_running_count=0
      set_agent MASTER_SYNC_DEGRADED_STATUS
      realtime_agent_state=MASTER_SYNC_DEGRADED_STATUS
    elif [[ "${current_pipe_state}" == FULL_SYNC \
            || "${current_pipe_state}" == QUEUED_FULL \
            || "${current_pipe_state}" == QUEUED ]]; then
      history_not_running_count=$((history_not_running_count + 1))
      set_agent MASTER_SYNC_DEGRADED_HISTORY_NOT_RUNNING
      if (( history_not_running_count >= PIPE_BAD_CONFIRMATIONS )); then
        # 只有连续多次查询成功且确认 full-sync 阶段缺失 history，才进入
        # 有退避的全量修复，避免一次状态抖动触发昂贵重建。
        transition_client >/dev/null 2>&1 || true
        atomic_write "${PIPE_STATE_FILE}" ERROR
        set_agent MASTER_PIPE_ERROR
        reset_realtime_health
        schedule_pipe_repair
      fi
    else
      # 本周期前面的 tri-state 检查已确认 realtime RUNNING。
      history_not_running_count=0
      atomic_write "${PIPE_STATE_FILE}" REALTIME
      evaluate_realtime_health
      set_agent "${realtime_agent_state}"
    fi
  fi

  last_role=${role}
  sleep "${INTERVAL}"
done
