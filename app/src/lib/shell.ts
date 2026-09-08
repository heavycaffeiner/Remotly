// Shell-quoting for command strings that cross into a remote POSIX shell.
//
// The app sends single-command strings over SSH exec, not argv. Anything that
// reaches the shell unquoted is interpreted: a label, path, or id containing a
// space, quote, or metacharacter becomes a different command. Every argument is
// therefore quoted at the one point where argv becomes a string.

/**
 * Quotes one argument for a POSIX shell.
 *
 * Single quotes protect everything except a single quote itself, which is
 * closed, escaped, and reopened: `'a'` + `\'` + `'b'` -> `a'b`. Without this a
 * value could run a command, and the value comes from the user.
 */
export function shellQuote(arg: string): string {
  return `'${arg.replace(/'/g, `'\\''`)}'`;
}

/**
 * Joins argv into one command string, quoting every element.
 *
 * The single quoting seam: nothing else in the caller list of commands builds a
 * shell string. Quoting the program name too is harmless (the shell still
 * resolves it through PATH) and keeps the rule uniform.
 */
export function joinShell(argv: readonly string[]): string {
  return argv.map(shellQuote).join(' ');
}
