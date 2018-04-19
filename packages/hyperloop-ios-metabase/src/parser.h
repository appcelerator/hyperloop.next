/**
 * Hyperloop Metabase Generator
 * Copyright (c) 2015 by Appcelerator, Inc.
 */
#ifndef HYPERLOOP_PARSER_H
#define HYPERLOOP_PARSER_H

#include <string>
#include <map>
#include <set>
#include "clang-c/Index.h"
#include "def.h"

namespace hyperloop {

	class ClassDefinition;
	class BlockDefinition;
	class TypeDefinition;
	class EnumDefinition;
	class VarDefinition;
	class FunctionDefinition;
	class StructDefinition;
	class UnionDefinition;
	class ParserContext;

	typedef std::map<std::string, ClassDefinition *> ClassMap;
	typedef std::map<std::string, TypeDefinition *> TypeMap;
	typedef std::map<std::string, EnumDefinition *> EnumMap;
	typedef std::map<std::string, VarDefinition *> VarMap;
	typedef std::map<std::string, FunctionDefinition *> FunctionMap;
	typedef std::map<std::string, StructDefinition *> StructMap;
	typedef std::map<std::string, UnionDefinition *> UnionMap;
	typedef std::map<std::string, std::map<std::string, BlockDefinition *>> BlockMap;

	/**
	 * state of the parser tree
	 */
	class ParserTree : public Serializable {
		public:
			ParserTree ();
			virtual ~ParserTree();

			void addClass (ClassDefinition *definition);
			void addExtension (ClassDefinition *definition);
			void addProtocol (ClassDefinition *definition);
			void addType (TypeDefinition *definition);
			void addEnum (EnumDefinition *definition);
			void addVar (VarDefinition *definition);
			void addFunction (FunctionDefinition *definition);
			void addStruct (StructDefinition *definition);
			void addUnion (UnionDefinition *definition);
			void addBlock (BlockDefinition *definition);

			ClassDefinition* getClass (const std::string &name);
			ClassDefinition* getExtension (const std::string &name);
			TypeDefinition* getType (const std::string &name);
			StructDefinition* getStruct (const std::string &name);
			UnionDefinition* getUnion (const std::string &name);
			EnumDefinition* getEnum (const std::string &name);

			bool hasClass (const std::string &name);
			bool hasExtension (const std::string &name);
			bool hasType (const std::string &name);
			bool hasStruct (const std::string &name);
			bool hasUnion (const std::string &name);
			bool hasEnum (const std::string &name);

			void setContext (ParserContext *);
			virtual Json::Value toJSON() const;

		private:
			ParserContext *context;
			ClassMap classes;
			ClassMap extensions;
			ClassMap protocols;
			TypeMap types;
			EnumMap enums;
			VarMap vars;
			FunctionMap functions;
			StructMap structs;
			UnionMap unions;
			BlockMap blocks;
	};

	/**
	 * information about the parse context
	 */
	class ParserContext {
		public:
			ParserContext (const std::string &_sdkPath, const std::string &_minVersion, bool exclude, const std::string &_frameworkFilter, const std::string &_frameworkName);
			~ParserContext();
			void updateLocation (const std::map<std::string, std::string> &location);
			inline const std::string& getSDKPath() const { return sdkPath; }
			inline const std::string& getMinVersion() const { return minVersion; }
			inline const std::string& getFrameworkFilter() const { return frameworkFilter; }
			inline const bool excludeSystemAPIs() const { return excludeSys; }
			inline const bool filterToSingleFramework() const { return frameworkFilter.size() > 0; }
			inline const std::string& getCurrentFilename () const { return filename; }
			inline const std::string& getCurrentLine () const { return line; }
			inline ParserTree* getParserTree() { return &tree; }
			std::string getFrameworkName() const;
			void setCurrent (Definition *current);
			inline Definition* getCurrent() { return current; }
			inline Definition* getPrevious() { return previous; }
			bool isSystemLocation (const std::string &location) const;
			bool isFrameworkLocation (const std::string& location);
			bool excludeLocation (const std::string& location);
			inline const std::set<std::string> getDependentFrameworks() const { return dependencies; }
		private:
			std::string sdkPath;
			std::string minVersion;
			bool excludeSys;
			std::string filename;
			std::string line;
			std::string frameworkFilter;
			std::string frameworkName;
			ParserTree tree;
			Definition* previous;
			Definition* current;
			std::set<std::string> dependencies;
	};

	/**
	 * parse the translation unit and return a ParserContext
	 */
	ParserContext* parse (CXTranslationUnit tu, std::string &sdkPath,  std::string &minVersion, bool excludeSystemAPIs, std::string &frameworkFilter, std::string &frameworkName);
}


#endif
