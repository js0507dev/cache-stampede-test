const path = require('path');
const fs = require('fs');

const srcDir = path.resolve(__dirname, 'src/scenarios');
const entries = {};

// 모든 시나리오 파일을 엔트리로 등록
fs.readdirSync(srcDir).forEach(file => {
  if (file.endsWith('.ts')) {
    const name = file.replace('.ts', '');
    entries[name] = path.resolve(srcDir, file);
  }
});

module.exports = {
  mode: 'production',
  entry: entries,
  output: {
    path: path.resolve(__dirname, 'dist'),
    libraryTarget: 'commonjs',
    filename: '[name].js',
  },
  resolve: {
    extensions: ['.ts', '.js'],
  },
  module: {
    rules: [
      {
        test: /\.ts$/,
        use: 'ts-loader',
        exclude: /node_modules/,
      },
    ],
  },
  target: 'web',
  externals: /^(k6|https?:\/\/)(\/.*)?/,
};
