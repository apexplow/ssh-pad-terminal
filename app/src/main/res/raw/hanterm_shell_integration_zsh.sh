# HanTerm shell integration v1 (Zsh)

[[ -o interactive ]] || return 0
[[ -z ${__HANTERM_ZSH_INTEGRATION_V1-} ]] || return 0
typeset -g __HANTERM_ZSH_INTEGRATION_V1=1

__hanterm_emit_state() {
    local phase=$1 in_tmux=0 session_id="" prefix=""

    if (( $+commands[tmux] )); then
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

    prefix=${prefix//;/}
    session_id=${session_id//;/}
    printf '\033]2;HANTERM;1;%s;%s;%s;%s\007' \
        "$phase" "$in_tmux" "$session_id" "$prefix"
}

__hanterm_zsh_ready() {
    __hanterm_emit_state READY
}

__hanterm_zsh_busy() {
    __hanterm_emit_state BUSY
}

autoload -Uz add-zsh-hook
add-zsh-hook precmd __hanterm_zsh_ready
add-zsh-hook preexec __hanterm_zsh_busy
