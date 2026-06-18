# BitBoard

BitBoard is a simple Android monitor and control app for Bitaxe miners.

It keeps local miners in one clean view and focuses on the details that matter during day-to-day use: hashrate, temperature, power, shares, uptime, Wi-Fi, pool data, and solo mining chance.

## What It Can Do

- Add and manage multiple Bitaxe miners by IP address
- Show live status, hashrate, power, temperature, shares, uptime, Wi-Fi, pool difficulty, and found blocks
- Calculate a weekly solo mining chance from the combined miner hashrate
- Keep offline miners visible with their last known values dimmed
- Switch between compact and expanded miner cards
- Show only online miners when needed
- Refresh manually with pull-to-refresh or use timed refresh intervals
- Refresh, restart, pause, or resume individual miners when supported
- Open a miner directly in the browser
- Control local Tasmota and Shelly power plugs
- Export and import miner and power plug settings as a JSON backup

## Screenshots

Screenshots are stored in [`metadata/screenshots`](metadata/screenshots):

- `bitboard01.png`
- `bitboard02.png`

## Icon

The app icon is stored in [`metadata/icon/bitboard.png`](metadata/icon/bitboard.png).

## Android

- Package: `com.zwegen.bitboard`
- Minimum SDK: 24
- Target SDK: 34
