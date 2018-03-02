#!/bin/sh
#
# Script buid building and packaging the Hyperloop iOS package
#
CWD=`pwd`
CURVERSION=`grep "^version:" manifest`
VERSION=`grep "^version:" manifest | cut -c 10-`
METABASE_VERSION=`grep "\"version\":" ../packages/hyperloop-ios-metabase/package.json | cut -d \" -f 4`
export TITANIUM_SDK="`node ../tools/tiver.js`"

XC=`which xcpretty`
CHECK="✓ "

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

mkdir -p build/zip/modules/iphone/hyperloop/$VERSION
cp manifest module.xcconfig build/zip/modules/iphone/hyperloop/$VERSION

# Build for the Apple JavaScriptCore built-in
echo "\nBuilding for JSCore ..."
xcodebuild clean >/dev/null
xcodebuild -sdk iphoneos -configuration Release GCC_PREPROCESSOR_DEFINITIONS='TIMODULE=1 USE_JSCORE_FRAMEWORK=1' ONLY_ACTIVE_ARCH=NO | xcpretty
xcodebuild -sdk iphonesimulator -configuration Debug GCC_PREPROCESSOR_DEFINITIONS='TIMODULE=1 USE_JSCORE_FRAMEWORK=1' ONLY_ACTIVE_ARCH=NO | xcpretty
lipo build/Debug-iphonesimulator/libhyperloop.a build/Release-iphoneos/libhyperloop.a -create -output build/zip/modules/iphone/hyperloop/$VERSION/libhyperloop-jscore.a >/dev/null 2>&1

# Build for the Titanium custom JavaScriptCore
echo "\nBuilding for TiJSCore ..."
xcodebuild clean >/dev/null
xcodebuild -sdk iphoneos -configuration Release GCC_PREPROCESSOR_DEFINITIONS='TIMODULE=1' ONLY_ACTIVE_ARCH=NO | xcpretty
xcodebuild -sdk iphonesimulator -configuration Debug GCC_PREPROCESSOR_DEFINITIONS='TIMODULE=1' ONLY_ACTIVE_ARCH=NO | xcpretty
lipo build/Debug-iphonesimulator/libhyperloop.a build/Release-iphoneos/libhyperloop.a -create -output build/zip/modules/iphone/hyperloop/$VERSION/libhyperloop-ticore.a

echo "\nPackaging iOS module..."
cp -R hooks build/zip/modules/iphone/hyperloop/$VERSION
cp -R ../hooks build/zip/modules/iphone/hyperloop/$VERSION
cp ../LICENSE build/zip/modules/iphone/hyperloop/$VERSION

# package the metabase into the .zip
echo "Packaging iOS metabase..."
cd ../packages/hyperloop-ios-metabase
rm *.tgz
npm pack >/dev/null 2>&1
cd $CWD

# Install dependencies
echo "Installing npm dependencies..."
cd build/zip/modules/iphone/hyperloop/$VERSION/hooks
npm i --production
npm i $CWD/../packages/hyperloop-ios-metabase/hyperloop-metabase-$METABASE_VERSION.tgz
rm -rf node_modules/findit/test
rm -rf package-lock.json
cd $CWD

# titanium requires at least this file so just create an empty one
echo 1 > $CWD/build/zip/modules/iphone/hyperloop/$VERSION/libhyperloop.a

cd $CWD/build/zip
rm -rf $CWD/hyperloop-iphone-$VERSION.zip
zip -q -r $CWD/hyperloop-iphone-$VERSION.zip * --exclude=*test* --exclude=*.DS_Store* --exclude=*.git* --exclude *.travis.yml*  --exclude *.gitignore*  --exclude *.npmignore* --exclude *CHANGELOG* --exclude *.jshintrc*

unset TITANIUM_SDK

echo "$CHECK Done packaging iOS module!\n"
exit 0
