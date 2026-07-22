# HanTerm shell integration v1 (Bash)
# shellcheck shell=bash

[[ $- == *i* ]] || return 0
[[ -z ${__HANTERM_BASH_INTEGRATION_V1-} ]] || return 0
__HANTERM_BASH_INTEGRATION_V1=1

__hanterm_emit_state() {
    local phase=$1 in_tmux=0 session_id="" prefix=""

    if command -v tmux >/dev/null 2>&1; then
        prefix=$(command tmux show-options -gv prefix 2>/dev/null || true)
    fi
    if [[ -n ${TMUX-} ]]; then
        in_tmux=1
        session_id=$(command tmux display-message -p '#{session_id}' 2>/dev/null || true)
        # tmux consumes pane OSC titles instead of forwarding them. Mirror the
        # active pane title to the client terminal so HanTerm receives this
        # protocol even when the pane is running an agent/TUI.
        command tmux set-option -q set-titles on 2>/dev/null || true
        command tmux set-option -q set-titles-string '#{pane_title}' 2>/dev/null || true
    fi

    # Protocol fields may not contain ';' or terminal controls.
    prefix=${prefix//;/}
    session_id=${session_id//;/}
    printf '\033]2;HANTERM;1;%s;%s;%s;%s\007' \
        "$phase" "$in_tmux" "$session_id" "$prefix"
}

__hanterm_prompt_ready() {
    __hanterm_emit_state READY
}

# PROMPT_COMMAND is an array on newer Bash and a string on older releases.
if declare -p PROMPT_COMMAND 2>/dev/null | command grep -q 'declare -a'; then
    PROMPT_COMMAND=(__hanterm_prompt_ready "${PROMPT_COMMAND[@]}")
elif [[ ${PROMPT_COMMAND-} != *"__hanterm_prompt_ready"* ]]; then
    PROMPT_COMMAND="__hanterm_prompt_ready${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
fi

# PS0 is expanded after a complete command is read and before it executes.
if [[ ${PS0-} != *"__hanterm_emit_state BUSY"* ]]; then
    PS0='$(__hanterm_emit_state BUSY)'"${PS0-}"
fi
