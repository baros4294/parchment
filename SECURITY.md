# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**. Don't open a public issue for them.

Include what you found, how to reproduce it, the Parchment version (Settings > Storage & Updates) and the platform. Never include real API keys; a revoked or made-up key is enough to show a problem.

You'll get a reply within a week. Fixes ship in a normal release, and the advisory is published once people can update.

## Supported versions

Only the latest release gets fixes. The app checks for updates itself (Settings > Storage & Updates).

## What counts

Anything that could expose API keys, chats or files, run code or commands without the approval Parchment asks for, reach a network address it shouldn't, or install an update that didn't come from this repository's releases.

How Parchment handles keys, files and network calls is described in the [README's privacy section](README.md#privacy).
