import { nodeResolve } from "@rollup/plugin-node-resolve";

export default {
  input: "src/index.js",
  output: {
    file: "../app/src/main/assets/editor/editor.bundle.js",
    format: "iife",
    name: "RNEditor",
  },
  plugins: [nodeResolve()],
};
