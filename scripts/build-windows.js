const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const rootDir = path.resolve(__dirname, "..");
const outDir = path.join(rootDir, "windows-app", "dist");
const outExe = path.join(outDir, "ZeroFeedWindows.exe");
const srcFile = path.join(rootDir, "windows-app", "ZeroFeed.Windows", "Program.cs");

fs.mkdirSync(outDir, { recursive: true });

const cscPath = "C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe";

if (fs.existsSync(cscPath)) {
  console.log("Compiling ZeroFeedWindows.exe using native Windows C# compiler (csc.exe)...");
  execFileSync(
    cscPath,
    [
      "/nologo",
      "/optimize+",
      "/target:exe",
      `/out:${outExe}`,
      "/r:System.dll",
      "/r:System.Core.dll",
      "/r:System.Drawing.dll",
      "/r:System.Windows.Forms.dll",
      srcFile
    ],
    { stdio: "inherit" }
  );
  console.log(`Built native Windows executable: ${outExe}`);
} else {
  console.log("csc.exe not found, falling back to dotnet build...");
  execFileSync(
    "dotnet",
    ["build", path.join(rootDir, "windows-app", "ZeroFeed.Windows", "ZeroFeed.Windows.csproj"), "-c", "Release", "-o", outDir],
    { stdio: "inherit" }
  );
}
