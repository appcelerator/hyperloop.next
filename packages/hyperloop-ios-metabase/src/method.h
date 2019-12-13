/**
 * Hyperloop Metabase Generator
 * Copyright (c) 2015 by Appcelerator, Inc.
 */
#ifndef HYPERLOOP_METHOD_H
#define HYPERLOOP_METHOD_H

#include "def.h"

namespace hyperloop {
	class ClassDefinition;

	/**
	 * Method definition
	 */
	class MethodDefinition : public Definition {
	public:
		MethodDefinition (CXCursor cursor, const std::string &name, ParserContext *ctx, bool instance, bool optional);
		~MethodDefinition ();
		Json::Value toJSON () const;
		void addArgument(CXCursor argumentCursor);
		void resolveReturnType();
	private:
		bool instance;
		bool optional;
		std::string encoding;
		Type *returnType;
		Arguments arguments;
		CXChildVisitResult executeParse(CXCursor cursor, ParserContext *context);
	};
}

#endif
