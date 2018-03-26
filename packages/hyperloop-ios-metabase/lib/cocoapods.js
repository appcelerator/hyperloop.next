'use strict';

const spawn = require('child_process').spawn; // eslint-disable-line security/detect-child-process
const exec = require('child_process').exec; // eslint-disable-line security/detect-child-process
const path = require('path');

const chalk = require('chalk');
const fs = require('fs-extra');
const semver = require('semver');

const util = require('./util');
const ModuleMetadata = require('./module_metadata').ModuleMetadata;
const Frameworks = require('./frameworks').Frameworks;

/**
 * parse the xcconfig file
 * @param {String} fn absolute path to a file
 * @returns {object}
 */
function parseCocoaPodXCConfig(fn) {
	const config = {};
	fs.readFileSync(fn).toString().split('\n').forEach(function (line) {
		const i = line.indexOf(' = ');
		if (i > 0) {
			const k = line.substring(0, i).trim();
			const v = line.substring(i + 2).trim();
			config[k] = v;
		}
	});
	return config;
}

/**
 * generate a map of xcode settings for CocoaPods
 * @param {String} basedir absolute path to base directory holding Pods
 * @returns {Object}
 */
function getCocoaPodsXCodeSettings(basedir) {
	const podDir = path.join(basedir, 'Pods');
	if (fs.existsSync(podDir)) {
		const target = path.join(podDir, 'Target Support Files'),
			name = fs.readdirSync(target).filter(function (n) { return n.indexOf('Pods-') === 0; })[0],
			dir = path.join(target, name);
		if (fs.existsSync(dir)) {
			const fn = path.join(dir, name + '.release.xcconfig');
			if (fs.existsSync(fn)) {
				const config = parseCocoaPodXCConfig(fn);
				if (config.PODS_ROOT) {
					// fix the PODS_ROOT to point to the absolute path
					config.PODS_ROOT = path.resolve(podDir);
				}
				return config;
			}
		}
	}
}

/**
 * Determines path of 'pod' binary
 * @return {Promise<string>} path to cocopods 'pod' binary
 */
function isPodInstalled() {
	return new Promise((resolve, reject) => {
		exec('which pod', function (err, stdout) {
			if (err) {
				return reject(new Error('CocoaPods not found in your PATH. You can install CocoaPods with: sudo gem install cocoapods'));
			}
			return resolve(stdout.trim());
		});
	});
}

/**
 * Determines the currently installed version of CocoaPods
 *
 * @returns {Promise<string>}
 */
function getCocoaPodsVersion() {
	return new Promise((resolve, reject) => {
		exec('pod --version', function (err, stdout) {
			if (err) {
				return reject(new Error('CocoaPods not found in your PATH. You can install CocoaPods with: sudo gem install cocoapods'));
			}
			return resolve(stdout.trim());
		});
	});
}

/**
 * Resturns an already resolved or rejected Promise
 * @param  {string} podfilePath path to 'Podfile' file
 * @param  {string} version     Pod version
 * @return {Promise}
 */
function validatePodfile(podfilePath, version) {
	const podfileContent = fs.readFileSync(podfilePath);
	if (semver.gte(version, '1.0.0')) {
		if (!/:integrate_targets\s*=>\s*false/.test(podfileContent)) {
			util.logger.error('Hyperloop requires your Podfile to include :integrate_target => false as an installation option:');
			util.logger.error('');
			util.logger.error('    install! \'cocoapods\', :integrate_targets => false');
			util.logger.error('');
			util.logger.error('For more information please see https://guides.cocoapods.org/syntax/podfile.html#install_bang');
			return Promise.reject(new Error('Your Podfile requires changes to use it with Hyperloop. Please see the note above on how to fix it.'));
		}
	}
	return Promise.resolve();
}

/**
 * Run 'pod install' in basedir
 * @param  {string} basedir   cwd to run pod install inside
 * @param  {[type]} podBinary path to 'pod' binary
 * @return {Promise}
 */
function podInstall(basedir, podBinary) {
	util.logger.info(`${chalk.green('CocoaPods')} dependencies found. This will take a few moments but will be cached for subsequent builds`);
	return new Promise((resolve, reject) => {
		const child = spawn(podBinary, [ 'install' ], { cwd: basedir });
		util.prefixOutput('CocoaPods', child.stdout, util.logger.trace);
		util.prefixOutput('CocoaPods', child.stderr, util.logger.warn);
		child.on('error', err => reject(err));
		child.on('exit', function (ec) {
			if (ec !== 0) {
				return reject(new Error('pod install returned a non-zero exit code: ' + ec));
			}
			return resolve();
		});
	});
}

function runPodInstallIfRequired(basedir) {
	const Pods = path.join(basedir, 'Pods'),
		Podfile = path.join(basedir, 'Podfile'),
		cacheToken =  util.createHashFromString(fs.readFileSync(Podfile)),
		cacheFile = path.join(basedir, 'build', '.podcache');

	fs.ensureDirSync(path.dirname(cacheFile));

	if (!fs.existsSync(Pods) || !fs.existsSync(cacheFile) || (fs.existsSync(cacheFile) && fs.readFileSync(cacheFile).toString() !== cacheToken)) {
		let podBinary;
		let podVersion;
		return isPodInstalled()
			.then(pod => {
				podBinary = pod;
				return getCocoaPodsVersion();
			})
			.then(version => {
				podVersion = version;
				return validatePodfile(Podfile, version);
			})
			.then(() => {
				util.logger.trace(`Found CocoaPods ${podVersion} (${podBinary})`);
				if (semver.lt(podVersion, '1.0.0')) {
					util.logger.error('Using a CocoaPods < 1.0.0 is not supported anymore. Please update your CocoaPods installation with: ' + chalk.blue('sudo gem install cocoapods'));
					return Promise.reject(new Error('Using a CocoaPods < 1.0.0 is not supported anymore.'));
				}
				return podInstall(basedir, podBinary);
			})
			.then(() => fs.writeFile(cacheFile, cacheToken));
	} else {
		return Promise.resolve();
	}
}

/**
* Runs CocoaPods to build any required libraries
*
* @param {String} basedir absolute path to build directory
* @param {iOSBuilder} builder iosBuilder
* @param {string} builder.xcodeTargetOS sdk type 'iphoneos' || 'iphonesimulator'
* @param {string} builder.iosSdkVersion sdk version (i.e. '11.2')
* @param {string} builder.minIosVer min ios version (i.e. 9.0')
* @param {string} builder.xcodeEnv xcode environment data
* @param {object} builder.xcodeEnv.executables info on various xcode related executables
* @param {string} builder.xcodeEnv.executables.xcodebuild path to xcodebuild
* @param {string} builder.xcodeTarget 'Release' || 'Debug
* @return {Promise}
*/
function runCocoaPodsBuild(basedir, builder) {
	const sdkType = builder.xcodeTargetOS,
		sdkVersion = builder.iosSdkVersion,
		minSDKVersion = builder.minIosVer,
		xcodesettings = builder.xcodeEnv.executables,
		// Make sure SDK version is always in MAJOR.MINOR format
		sdk = sdkType + (/\d+\.\d+\.\d+/.test(sdkVersion) ? sdkVersion.substring(0, sdkVersion.lastIndexOf('.')) : sdkVersion),
		productsDirectory = path.join(basedir, 'build/iphone/build/Products'),
		buildConfigurationName = builder.xcodeTarget,
		args = [
			'-configuration', buildConfigurationName,
			'-alltargets',
			'IPHONEOS_DEPLOYMENT_TARGET=' + minSDKVersion,
			'-sdk', sdk,
			'SYMROOT=' + productsDirectory,
			'ONLY_ACTIVE_ARCH=NO'
		];

	return new Promise((resolve, reject) => {
		const buildOutDir = path.join(productsDirectory, buildConfigurationName + '-' + sdkType),
			runDir = path.join(basedir, 'Pods'),
			child = spawn(xcodesettings.xcodebuild, args, { cwd: runDir });
		util.logger.debug('running ' + xcodesettings.xcodebuild + ' ' + args.join(' ') + ' ' + runDir);
		util.logger.info('Building ' + chalk.green('CocoaPods') + ' dependencies');
		util.prefixOutput('CocoaPods', child.stdout, util.logger.trace);
		util.prefixOutput('CocoaPods', child.stderr, util.logger.warn);
		child.on('error', err => reject(err));
		child.on('exit', function (ec) {
			if (ec !== 0) {
				return reject(new Error('the xcodebuild failed running from ' + runDir));
			}
			if (!fs.existsSync(buildOutDir)) {
				return reject(new Error('xcodebuild did not produce the expected CocoaPods libraries at ' + buildOutDir));
			}

			return resolve();
		});
	});
}

/**
 * Calculates a cache token based on the Podfile checksum and all installed pod
 * specs checksums.
 *
 * If one of these checksums change, either the Podfile changed or a Pod was
 * updated/installed/removed, resulting in a changed cache token and the
 * CocoaPods symbol mapping will be regenerated.
 *
 * @param {string} podLockfilePathAndFilename absolute path to the Pod.lock file
 * @return {string} The generated cache token
 */
function calculateCacheTokenFromPodLockfile(podLockfilePathAndFilename) {
	if (!fs.existsSync(podLockfilePathAndFilename)) {
		throw new Error('No Podfile.lock found in your project root. ');
	}
	const cacheTokenData = { podfile: '', specs: [] };
	const podLockfileContent = fs.readFileSync(podLockfilePathAndFilename).toString();
	const specChecksumRegex = /[ ]{2}[^.][^\s/]*:\s(.*)/ig;
	let checksumMatches = specChecksumRegex.exec(podLockfileContent);
	if (checksumMatches === null) {
		throw new Error('Could not read spec checksums from Podfile.lock');
	}
	while (checksumMatches !== null) {
		cacheTokenData.specs.push(checksumMatches[1]);
		checksumMatches = specChecksumRegex.exec(podLockfileContent);
	}
	const podfileChecksumMatch = podLockfileContent.match(/PODFILE CHECKSUM: (.*)/);
	if (podfileChecksumMatch === null) {
		throw new Error('Could not read Podfile checksum from Podfile.lock');
	}
	cacheTokenData.podfile = podfileChecksumMatch[1];
	return util.createHashFromString(JSON.stringify(cacheTokenData));
}

/**
 * Checks if cocoapods is installed and used. If so, runs pod install, Runs
 * a CocoaPods build, and then grabs the settings used and returns that.
 *
 * @param  {Object}   builder  iOSBuilder
 * @param  {string} builder.projectDir path to project dir where Podfile lives
 * @return {Promise<Object>}
 */
function installPodsAndGetSettings(builder) {
	const basedir = builder.projectDir;
	const Podfile = path.join(basedir, 'Podfile');
	if (!fs.existsSync(Podfile)) {
		util.logger.debug('No CocoaPods Podfile found. Skipping ...');
		return Promise.resolve();
	}

	const content = fs.readFileSync(Podfile).toString();

	if (content.indexOf('pod ') === -1) {
		util.logger.warn('Podfile found, but no Pods specified. Skipping ...');
		return Promise.resolve();
	}

	if (/^use_frameworks!$/m.test(content) === false) {
		util.logger.warn('Using CocoaPods without the "use_frameworks!" flag is deprecated since Hyperloop 3.0.2 and will be removed in Hyperloop 4.0.0.');
		util.logger.warn('Please add "use_frameworks!" to your Podfile to remain compatible with future versions of Hyperloop.');
	}

	return runPodInstallIfRequired(basedir)
		.then(() => runCocoaPodsBuild(basedir, builder))
		.then(() => {
			const settings = getCocoaPodsXCodeSettings(basedir);
			util.logger.trace(`${chalk.green('CocoaPods')} Xcode settings will be: ${JSON.stringify(settings, null, 2)}`);
			return Promise.resolve(settings);
		});
}

/**
 * Search the static library headers for "modules" and return the mapping of those found.
 * @param  {string} staticLibrariesHeaderPath path to static librariy headers
 * @return {Map<string, ModuleMetadata>}
 */
function gatherStaticLibraries(staticLibrariesHeaderPath) {
	const modules = new Map();
	if (!fs.existsSync(staticLibrariesHeaderPath)) {
		return modules;
	}

	// Look in path, assume each subdir is a "framework" whose name is the subdir name
	// The path is the sub-dir, the umbrella header is assumed to be a file under the sub-dir with the same name!
	const frameworkNames = gatherSubdirectories(staticLibrariesHeaderPath);
	frameworkNames.forEach(frameworkName => {
		const libraryPath = path.join(staticLibrariesHeaderPath, frameworkName);
		const moduleMetadata = new CocoapodStaticFramework(frameworkName, [ path.join(libraryPath, `${frameworkName}.h`) ]);
		modules.set(moduleMetadata.name, moduleMetadata);
	});
	return modules;
}

/**
 * Check for any frameworks under the CocoaPods FRAMEWORK_SEARCH_PATHS
 * @param  {string[]} frameworkSearchPaths [description]
 * @param  {string} podsRoot             [description]
 * @param  {string} podsConfigBuildDir   [description]
 * @return {Promise<Map<string, ModuleMetadata>>}
 */
function gatherFrameworksFromSearchPaths(frameworkSearchPaths, podsRoot, podsConfigBuildDir) {
	const promises = frameworkSearchPaths.map(frameworkSearchPath => {
		frameworkSearchPath = frameworkSearchPath.replace('${PODS_ROOT}', podsRoot); // eslint-disable-line no-template-curly-in-string
		// TIMOB-25829: CocoaPods < 1.4.0 uses $PODS_CONFIGURATION_BUILD_DIR, 1.4.0+ uses ${PODS_CONFIGURATION_BUILD_DIR}
		// Remove regex once we bump the minimum version to 1.4.0+
		frameworkSearchPath = frameworkSearchPath.replace(/\$(\{)?(PODS_CONFIGURATION_BUILD_DIR)(\})?/, podsConfigBuildDir);
		frameworkSearchPath = frameworkSearchPath.replace(/"/g, '');
		if (!fs.existsSync(frameworkSearchPath)) {
			return Promise.resolve(new Map());
		}

		const frameworksEntries = fs.readdirSync(frameworkSearchPath).filter(entryName => /\.framework$/.test(entryName));
		const frameworks = frameworksEntries.map(searchPathEntry => {
			return new CocoapodFramework(path.join(frameworkSearchPath, searchPathEntry));
		});
		return Promise.all(frameworks);
	});
	return Promise.all(promises).then(arrayOfFrameworks => {
		const modules = new Map();
		arrayOfFrameworks.forEach(framework => {
			modules.set(framework.name, framework);
		});
		return Promise.resolve(modules);
	});
}

/**
 * Given a directory, gather the names of all the direct sub-directories.
 * @param  {String} dir [description]
 * @return {String[]}
 */
function gatherSubdirectories(dir) {
	const result = [];
	const files = fs.readdirSync(dir);
	files.forEach(function (filename) {
		const fullPath = path.join(dir, filename);
		if (fs.statSync(fullPath).isDirectory()) {
			result.push(filename);
		}
	});
	return result;
}

/**
 * Gets the full path to the built products directory for the current Xcode build
 * configuration name and SDK type.
 *
 * @param {String} basePath Project root path
 * @param {String} configurationName Active configuration name, i.e. Debug, Release
 * @param {String} sdkType Active SDK type, i.e. iphone or iphonesimulator
 * @return {String} Full path the the products directory
 */
function getBuiltProductsRootPath(basePath, configurationName, sdkType) {
	return path.join(basePath, 'build/iphone/build/Products', configurationName + '-' + sdkType);
}

/**
 * Generates a mapping of symbols for CocoaPods third-party libraries and
 * frameworks.
 *
 * This can process both static libraries and frameworks (dynamic frameworks
 * need to expose an ObjC Interface Header).
 *
 * @param {Object} builder iOSBuilder instance
 * @param {String} builder.projectDir path to project directory
 * @param {String} builder.xcodeTarget Active configuration name, i.e. 'Debug', 'Release'
 * @param {String} builder.xcodeTargetOS Active SDK type, i.e. 'iphone' or 'iphonesimulator'
 * @param {Object} settings sdk settings - from #installPodsAndGetSettings!
 * @returns {Promise<Map<string, ModuleMetadata>>}
 */
function generateCocoaPodsMetadata(builder, settings) {
	return new CocoapodsFrameworks(builder, settings).load();
}

class CocoapodStaticFramework extends ModuleMetadata {
	constructor(name, headers) {
		super(name, headers[0], ModuleMetadata.MODULE_TYPE_STATIC);
	}
}

class CocoapodFramework extends ModuleMetadata {
	constructor(frameworkPath) {
		super(path.basename(frameworkPath, '.framework'), frameworkPath, ModuleMetadata.MODULE_TYPE_DYNAMIC);
		this.sniff();
	}
}

class CocoapodsFrameworks extends Frameworks {

/**
 * @param {Object} builder iOSBuilder instance
 * @param {String} builder.projectDir path to project directory
 * @param {String} builder.xcodeTarget Active configuration name, i.e. 'Debug', 'Release'
 * @param {String} builder.xcodeTargetOS Active SDK type, i.e. 'iphone' or 'iphonesimulator'
 * @param {Object} settings sdk settings - from #installPodsAndGetSettings!
 */
	constructor(builder, settings) {
		super();
		this.builder = builder;
		this.settings = settings;
	}

	cacheFile() {
		const podLockfilePathAndFilename = path.join(this.builder.projectDir, 'Podfile.lock');
		const cacheToken = calculateCacheTokenFromPodLockfile(podLockfilePathAndFilename);
		return path.join(this.cacheDir, `metabase-cocoapods-${cacheToken}.json`);
	}

	/**
	 * The actual work to detect/load frameworks from original data.
	 * @return {Promise<Map<string, ModuleMetadata>>}
	 */
	detect() {
		// Check static libraries
		const podDir = path.join(this.builder.projectDir, 'Pods');
		const staticLibrariesHeaderPath = path.join(podDir, 'Headers', 'Public');
		const modules = gatherStaticLibraries(staticLibrariesHeaderPath);

		// Now check FRAMEWORK_SEARCH_PATHS!
		const frameworkSearchPaths = (this.settings.FRAMEWORK_SEARCH_PATHS || '').split(' ');
		const cocoaPodsConfigurationBuildDir = getBuiltProductsRootPath(this.builder.projectDir, this.builder.xcodeTarget, this.builder.xcodeTargetOS);
		return gatherFrameworksFromSearchPaths(frameworkSearchPaths, this.settings.PODS_ROOT, cocoaPodsConfigurationBuildDir)
			.then(dynamicModules => {
				// Combine dynamic modules with static
				dynamicModules.forEach((value, key) => {
					modules.set(key, value);
				});
				return Promise.resolve(modules);
			});
	}
}

exports.installPodsAndGetSettings = installPodsAndGetSettings;
exports.generateCocoaPodsMetadata = generateCocoaPodsMetadata;
