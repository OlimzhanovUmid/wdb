# Monetization strategy

_Last updated: 2026-09-05._

wdb is open source and stays that way. This note records how the project can attract
financial support without compromising that — the reasoning, not a commitment.

## Two users, one line

wdb serves two very different users, and the free/paid line follows that split — never
by crippling existing features:

```
  INDIVIDUAL DEV                          COMPANY / FLEET OPERATOR
  ─ few machines, one wall                ─ many machines across stores/sites
  ─ hot-reload, screenshot, MCP           ─ POS terminals, kiosks, signage
  ─ runs it from their own IDE            ─ needs reliability, audit, scale
  = the adoption engine                   = the paying customer
  → FREE forever (OSS + goodwill)         → pays for ops/management
```

The point-of-sale use case is the tell: the real revenue is businesses running Compose
Desktop fleets (POS / kiosk / signage), and *fleet operations* is what they pay for.
A dev toolkit is not.

## Open-core split

| Tier | Features | Status |
|---|---|---|
| **Free / OSS** (Apache-2.0) | CLI, IntelliJ plugin, MCP, hot-reload, screenshot, semantic-tree, deploy/run/rollback, self-update, single-wall control | built |
| **Paid** (fleet/ops) | Hub/dashboard daemon, staged/canary rollout across a fleet, machine groups/tags, auth + RBAC + audit log, log persistence + search, hang-detect/crash alerts, cross-site relay (walls beyond one LAN), SLA/priority support | backlog |

Everything on the paid side is currently unbuilt backlog, so nothing that exists today is
paywalled. The existing backlog (hub daemon, auth, log persistence, hang-detect, mDNS/relay)
*is* the paid tier.

## Phased approach

1. **Now — sponsors (cheap, OSS-friendly):** `.github/FUNDING.yml` (GitHub Sponsors +
   Buy Me a Coffee) and a sponsor link in the README and the plugin. Folded into the
   `add-release-pipeline` change. Expect coffee-tier yield — this is goodwill, not funding.
2. **Later — the hub is the real lever:** recurring B2B for fleet operators. Donations rarely
   fund real work on a niche tool; the hub does.
3. **Marketplace:** publish the plugin free now for adoption. JetBrains Marketplace has
   built-in paid/freemium billing, so a future "Team" tier can gate hub-connected features
   (needs a company/tax setup — not blocking).

## License

The owner is the sole copyright holder and can dual-license at any time. Keep the core
**Apache-2.0** (maximum adoption, enterprise-safe). Ship the future paid hub/ops layer as a
*separate* proprietary or source-available (BSL-style) component so the permissive core stays
clean. Avoid AGPL — it scares enterprises away from adoption.
