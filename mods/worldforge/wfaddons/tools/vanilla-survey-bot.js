"use strict";

const mineflayer = require("mineflayer");

const host = process.env.WF_SURVEY_HOST || "127.0.0.1";
const port = Number(process.env.WF_SURVEY_PORT || process.argv[2] || 25565);
const username = process.env.WF_SURVEY_USERNAME || process.argv[3] || "MaqanSurveyor";

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error("Invalid survey server port");
}

const bot = mineflayer.createBot({
  host,
  port,
  username,
  auth: "offline",
  version: "1.20.1",
  hideErrors: false
});

let spawned = false;
bot.once("spawn", () => {
  spawned = true;
  console.log("SURVEY_BOT_READY=" + username);
});

bot.on("kicked", reason => {
  console.error("SURVEY_BOT_KICKED=" + String(reason));
  process.exitCode = 2;
});

bot.on("error", error => {
  console.error("SURVEY_BOT_ERROR=" + (error && error.stack ? error.stack : String(error)));
  process.exitCode = 2;
});

bot.on("end", reason => {
  console.error("SURVEY_BOT_DISCONNECTED=" + String(reason || "unknown"));
  if (spawned) process.exitCode = 2;
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    try { bot.quit("survey complete"); } catch (_) {}
    setTimeout(() => process.exit(0), 250).unref();
  });
}
