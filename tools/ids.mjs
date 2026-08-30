// The identifiers that point at *this* household's spreadsheet and phone.
//
// They are not secrets in the usual sense. Knowing them grants nothing on its
// own: the Firestore rules deny every unauthenticated read (verified - a bare
// GET of the device document returns 403), and the spreadsheet is private
// (an anonymous CSV export redirects to a sign-in page). Anyone holding these
// strings still needs a credential.
//
// They are kept out of the repository anyway, because the repository is public
// and an identifier is a standing head start. It costs nothing to withhold the
// second half of a pair when the first half is a file we already refuse to
// commit, and it means a future mistake in the rules is not immediately
// exploitable by someone who cloned the repo months earlier.
//
// Resolution order is environment first, so GitHub Actions can pass secrets
// without a file on disk, then a local file for running the tools by hand.
// The file is read relative to this module rather than the working directory,
// because the tools are run both from the repository root and from tools/.
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";

const LOCAL = fileURLToPath(new URL("./ids.local.json", import.meta.url));

let fileIds = null;
function fromFile() {
  if (fileIds === null) {
    fileIds = existsSync(LOCAL) ? JSON.parse(readFileSync(LOCAL, "utf8")) : {};
  }
  return fileIds;
}

/**
 * One identifier, or a clear explanation of how to supply it.
 *
 * Throwing beats returning undefined: an undefined id becomes a Firestore path
 * like `users/undefined/devices/...`, which fails much later and much less
 * legibly than a missing-configuration error at startup.
 */
export function id(name) {
  const fromEnv = process.env[`YTM_${name}`];
  if (fromEnv) return fromEnv;

  const value = fromFile()[name];
  if (value) return value;

  throw new Error(
    `Missing identifier ${name}.\n` +
      `Set YTM_${name} in the environment, or add "${name}" to tools/ids.local.json.\n` +
      `See tools/ids.example.json for the shape, and the README for where each value comes from.`
  );
}
