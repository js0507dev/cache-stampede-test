const esbuild = require('esbuild');
const fs = require('fs');
const path = require('path');

const scenariosDir = path.resolve(__dirname, 'src/scenarios');
const distDir = path.resolve(__dirname, 'dist');
const resultsDir = path.resolve(__dirname, 'results');

// dist 디렉토리 생성
if (!fs.existsSync(distDir)) {
  fs.mkdirSync(distDir, { recursive: true });
}

// results 디렉토리 생성 (k6 handleSummary에서 파일 저장 시 필요)
if (!fs.existsSync(resultsDir)) {
  fs.mkdirSync(resultsDir, { recursive: true });
}

// 모든 시나리오 파일 찾기
const scenarios = fs.readdirSync(scenariosDir)
  .filter(file => file.endsWith('.ts'))
  .map(file => path.resolve(scenariosDir, file));

// 각 시나리오 빌드
async function build() {
  for (const scenario of scenarios) {
    const name = path.basename(scenario, '.ts');

    await esbuild.build({
      entryPoints: [scenario],
      bundle: true,
      outfile: path.resolve(distDir, `${name}.js`),
      platform: 'neutral',
      target: 'es2020',
      format: 'esm',
      external: ['k6', 'k6/*'],
      sourcemap: false,
      minify: false,
    });

    console.log(`Built: ${name}.js`);
  }
}

build().catch((err) => {
  console.error(err);
  process.exit(1);
});
