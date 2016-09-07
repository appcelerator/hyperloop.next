#!/bin/sh
#
# Script buid building and packaging the Hyperloop iOS package
#
CWD=`pwd`
CURVERSION=`grep "^version:" manifest`
VERSION=`grep "^version:" manifest | cut -c 10-`
METABASE=$CWD/build/zip/plugins/hyperloop/$VERSION/node_modules/hyperloop-metabase
export TITANIUM_SDK="`node ../tools/tiver.js`"

XC=`which xcpretty`
if [ $? -eq 1 ];
then
	gem install xcpretty
fi

rm -rf build

if [ "${CI}" = "1" ];
then
	echo "Testing ..."
	xcodebuild clean >/dev/null
	xcodebuild -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 6' -scheme hyperloop -target Tests -configuration Debug GCC_PREPROCESSOR_DEFINITIONS='USE_JSCORE_FRAMEWORK=1' test | xcpretty -r junit
	if [ $? -ne 0 ];
	then
		exit $?
	fi
fi

echo "Building ..."

mkdir -p build/zip/modules/iphone/hyperloop/$VERSION
mkdir -p build/zip/plugins/hyperloop/$VERSION/hooks/ios
mkdir -p build/zip/plugins/hyperloop/$VERSION/node_modules/hyperloop-metabase
cd build/zip/plugins/hyperloop/$VERSION
npm install findit --production >/dev/null 2>&1
rm -rf node_modules/findit/test
cd $CWD
cp manifest module.xcconfig build/zip/modules/iphone/hyperloop/$VERSION

# Build for the Apple JavaScriptCore built-in
xcodebuild clean >/dev/null
xcodebuild -sdk iphoneos -configuration Release GCC_PREPROCESSOR_DEFINITIONS='TIMODULE=1 USE_JSCORE_FRAMEWORK=1' ONLY_ACTIVE_ARCH=NO | xcpretty
xcodebuild -sdk iphonesimulator -configuration Debug GCC_PREPROCESSOR_DEFINITIONS='TIMODULE=1 USE_JSCORE_FRAMEWORK=1' ONLY_ACTIVE_ARCH=NO | xcpretty
lipo build/Debug-iphonesimulator/libhyperloop.a build/Release-iphoneos/libhyperloop.a -create -output build/zip/modules/iphone/hyperloop/$VERSION/libhyperloop-jscore.a >/dev/null 2>&1

# Build for the Titanium custom JavaScriptCore
xcodebuild clean >/dev/null
xcodebuild -sdk iphoneos -configuration Release GCC_PREPROCESSOR_DEFINITIONS='TIMODULE=1' ONLY_ACTIVE_ARCH=NO | xcpretty
xcodebuild -sdk iphonesimulator -configuration Debug GCC_PREPROCESSOR_DEFINITIONS='TIMODULE=1' ONLY_ACTIVE_ARCH=NO | xcpretty
lipo build/Debug-iphonesimulator/libhyperloop.a build/Release-iphoneos/libhyperloop.a -create -output build/zip/modules/iphone/hyperloop/$VERSION/libhyperloop-ticore.a

echo "Packaging ..."
# make sure to update the plugin with the latest version in it's package.json
node -e "j=JSON.parse(require('fs').readFileSync('plugin/package.json'));j.version='$VERSION';console.log(JSON.stringify(j,null,2))" > build/zip/plugins/hyperloop/$VERSION/package.json

cp ../plugins/hyperloop.js build/zip/plugins/hyperloop/$VERSION/hooks/hyperloop.js
cp plugin/hyperloop.js build/zip/plugins/hyperloop/$VERSION/hooks/ios
cp plugin/filter.sh build/zip/plugins/hyperloop/$VERSION/hooks/ios
cp ../LICENSE.md build/zip/plugins/hyperloop/$VERSION
cp ../LICENSE.md build/zip/modules/iphone/hyperloop/$VERSION

# package the metabase into the .zip
cd ../metabase/ios
./build.sh >/dev/null
rm *.tgz
npm pack >/dev/null 2>&1
mkdir -p $CWD/build/npm
cp *.tgz $CWD/build/npm
cd $CWD/build/npm
tar xfz *.tgz
rm -rf *.tgz
cd package
npm i --production >/dev/null 2>&1
rm -rf unittest
cp -R * $METABASE
rm -rf $METABASE/hyperloop-metabase.xcodeproj $METABASE/hyperloop-metabase.xcodeproj $METABASE/src $METABASE/unittest $METABASE/include $METABASE/build

# titanium requires at least this file so just create an empty one
echo 1 > $CWD/build/zip/modules/iphone/hyperloop/$VERSION/libhyperloop.a

cd $CWD/build/zip
rm -rf $CWD/hyperloop-iphone-$VERSION.zip
zip -q -r $CWD/hyperloop-iphone-$VERSION.zip * --exclude=*test* --exclude=*.DS_Store* --exclude=*.git* --exclude *.travis.yml*  --exclude *.gitignore*  --exclude *.npmignore* --exclude *CHANGELOG* --exclude *.jshintrc*

unset TITANIUM_SDK

echo "Done...!"
exit 0
