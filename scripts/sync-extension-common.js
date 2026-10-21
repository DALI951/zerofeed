const fs = require("node:fs");
const path = require("node:path");

const rootDir = path.resolve(__dirname, "..");
const extCommonDir = path.join(rootDir, "browser-extension", "common");

fs.mkdirSync(extCommonDir, { recursive: true });
fs.copyFileSync(
  path.join(rootDir, "common", "default-rules.json"),
  path.join(extCommonDir, "default-rules.json")
);
fs.copyFileSync(
  path.join(rootDir, "common", "rule-engine.js"),
  path.join(extCommonDir, "rule-engine.js")
);

console.log("Synced common/default-rules.json and common/rule-engine.js to browser-extension/common/");
