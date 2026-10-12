// Builds the vendored Graphic Walker: the library's own (unbundled) modules, with Vite's `?worker&inline` imports done as inline blob workers, and the map
// libraries (react-leaflet, whose Hippocratic-2.1 licence the product does not take in) replaced by an empty stand-in. Output: walker.js (an IIFE) and meta.json.
import * as esbuild from 'esbuild';
import path from 'node:path';
import fs from 'node:fs';

const define = { 'process.env.NODE_ENV': '"production"', 'import.meta.env.DEV': 'false', 'import.meta.env.PROD': 'true', 'import.meta.env.MODE': '"production"', 'import.meta.env.SSR': 'false', 'import.meta.env.BASE_URL': '"/"' };
const loaders = { '.png': 'dataurl', '.svg': 'dataurl', '.ico': 'dataurl', '.woff2': 'dataurl', '.woff': 'dataurl', '.ttf': 'dataurl', '.css': 'text' };
const alias = { 'react-leaflet': path.resolve('stubs/leaflet-stub.js'), '@react-leaflet/core': path.resolve('stubs/leaflet-stub.js') };

const workerPlugin = {
  name: 'inline-workers',
  setup(build) {
    build.onResolve({ filter: /\?worker&inline$/ }, (args) => {
      const base = path.resolve(args.resolveDir, args.path.replace(/\?worker&inline$/, ''));
      const file = [base, base + '.js'].find((f) => fs.existsSync(f));
      return { path: file, namespace: 'gw-worker' };
    });
    // Vite's `?inline` CSS imports: Graphic Walker writes its styles into a shadow root as text. `index.css` is the library's own file; the Tailwind sheet is the one the library's
    // own build compiled (tailwind.compiled.css, cut out of graphic-walker.es.js by find_css.py), because the sources hold only the `@tailwind` directives.
    build.onResolve({ filter: /\.css\?inline$/ }, (args) => {
      const name = args.path.replace(/\?inline$/, '');
      const file = name.startsWith('tailwindcss/') ? path.resolve('tailwind.compiled.css') : path.resolve(args.resolveDir, name);
      return { path: file, namespace: 'gw-css' };
    });
    build.onLoad({ filter: /.*/, namespace: 'gw-css' }, (args) => ({ contents: 'export default ' + JSON.stringify(fs.readFileSync(args.path, 'utf8')) + ';\n', loader: 'js' }));
    build.onLoad({ filter: /.*/, namespace: 'gw-worker' }, async (args) => {
      const out = await esbuild.build({ entryPoints: [args.path], bundle: true, minify: true, format: 'iife', platform: 'browser', write: false, define, loader: loaders, alias });
      const code = out.outputFiles[0].text;
      return {
        contents: 'const code = ' + JSON.stringify(code) + ';\nexport default class InlineWorker extends Worker { constructor(options) { super(URL.createObjectURL(new Blob([code], { type: "text/javascript" })), options); } }\n',
        resolveDir: path.dirname(args.path),
        loader: 'js'
      };
    });
  }
};

const result = await esbuild.build({ entryPoints: ['walker_entry2.js'], bundle: true, minify: true, format: 'iife', platform: 'browser', define, loader: loaders, alias, plugins: [workerPlugin],
  metafile: true, outfile: 'walker.js', logLevel: 'warning' });
// Graphic Walker names a few hosts it would fetch from (a stylesheet for its map, an image, its AI and sample-data services, the map tiles). The frame it runs in may not
// connect anywhere (connect-src 'none'), so these are made to point at a name that cannot exist (RFC 2606 .invalid): nothing is attempted, and the policy stays a second lock.
const HOSTS = /https:\/\/(unpkg\.com|imagedelivery\.net|api\.kanaries\.net|pub-[0-9a-f]+\.r2\.dev|graphic-walker\.kanaries\.net|docs\.kanaries\.net|bit\.ly|\{s\}\.tile\.openstreetmap\.org)/g;
fs.writeFileSync('walker.js', fs.readFileSync('walker.js', 'utf8').replace(HOSTS, 'https://blocked.invalid'));
fs.writeFileSync('meta.json', JSON.stringify(result.metafile));
console.log('ok', fs.statSync('walker.js').size);
