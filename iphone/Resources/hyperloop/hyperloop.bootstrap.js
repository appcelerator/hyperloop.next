// The file required-in here will be generated by the hyperloop build hook if at least 1 native reference exists.
// Will provide require/import bindings between native type name to hyperloop generated JS file.
if (Ti.Filesystem.getFile(Ti.Filesystem.resourcesDirectory, 'hyperloop', 'hyperloop.bindings.js').exists()) {
	require('/hyperloop/hyperloop.bindings');
}