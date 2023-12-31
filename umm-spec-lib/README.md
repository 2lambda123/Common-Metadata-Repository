# umm-spec-lib

The UMM Spec lib contains JSON schemas that defined the Unified Metadata Model. The UMM defines common data structures for representing metadata. The UMM Spec Lib also contains mappings between the XML metadata standards (ECHO10, DIF 9, DIF 10, ISO 19115 SMAP and ISO 19115 MENDS) and the UMM. The UMM is represented as Clojure records in the UMM Spec lib. The mappings provide a way for programmatic conversion between XML and UMM.

## Library Layout

Lists major parts of the library.

  * src/cmr/umm-spec/
    * models/ - Contains clojure namespaces that were generated from the UMM JSON schemas.
    * json_schema.clj - Contains code for loading JSON schemas.
    * record_generator.clj - Generates clojure records from json schemas. Must be manually done when the JSON schemas are updated.
    * umm_mappings/* - Code that defines the XML to UMM Mapping DSL, XML Parser, and uses of the DSL to define the mappings between formats.
    * xml_mappings/* - Code that defines the UMM to XML Mapping DSL, XML Generator, and uses of the DSL to define the mappings between formats.
  * resources/
    * json-schemas/ - Contains the UMM JSON schemas


## Generating Clojure Records from UMM JSON Schemas

The JSON schemas in `resource/json-schemas` define the structure of the UMM. These are used as source documents for generating Clojure records that can store the same data as the UMM. The records are generated into `src/cmr/umm-spec/models`.

JSON schemas can define complex types like the following example:

```
{
  "$schema": "http://json-schema.org/draft-04/schema#",
  "title": "Book",
  "type": "object",
  "properties": {
    "Author": {
      "description": "The author of this book",
      "$ref": "umm-cmn-json-schema.json#/definitions/AuthorType"
    },
    "Title": {
      "description": "The title of the book",
      "type": "string"
    }
  }
}
```

That defines a Book type as the root of the schema which has an Author and a Title. The Author is a type defined in another schema and Title is a string.

We would like to represent a Book type in Clojure like the following

```
(defrecord Book
  [
   ;; The author of this book
   Author

   ;; The title of the book
   Title
  ])
```

The code in `cmr.umm-spec.record-generator` can generate records from a schema. The UMM Clojure records can be generated by running `lein generate-umm-records` in this project.

## Update of umm-cmn-json-schema.json

We need to keep the latest version of the umm-cmn-json-schema.json in sync for all concept types. When the umm-cmn-json-schema.json is updated for one concept type, the corresponding files should be updated for all concept types.

## Implementing mapping for a new field

Adding mappings for a new or existing field in the UMM should be done at the same time across all formats.

### 1. Obtain the XPaths and example XML samples.

Contact Erich Reiter (erich.e.reiter@nasa.gov) to obtain these.

### 2. Implement tests for each format.

Add roundtrip conversion tests for each format in `cmr.umm-spec.test.generate-and-parse`

### 3. Implement the UMM to XML mappings for each format.

The UMM to XML mappings are in files named `src/cmr/umm_spec/xml_mappings/<format>.clj`

See the DSL documentation and existing formats for examples.

If the conversion from UMM to XML is lossy then update cmr.umm-spec.test.expected-conversion

### 4. Implement the XML to UMM mappings for each format.

The XML to UMM mappings are in files named `src/cmr/umm_spec/umm_mappings/<format>.clj`

See the DSL documentation and existing formats for examples.

### 5. Update other tests that use UMM Spec

* Update the example record in cmr.system-int-test.ingest.translation-test.

### 6. Update the schemas in the EMFD project

Update the EMFD project with the new directory. If you are unable to see the repository, a member of the CMR team can make the appropriate changes for you.

## Adding a new schema version

Depending on the changes to the UMM JSON schema, a new schema version may be required.
### 1. Add schema files

Create a new folder for the schema version in `resources/json_schemas/umm` and copy the files from the previous schema version into that folder. Make schema changes to the files in that folder.

### 2. Make model and code changes to go with the new version

Update the files in `src/cmr/umm_spec/models` with any UMM model changes then make the code changes needed in each format.

### 3. Update the UMM latest version

In `src/cmr/umm_spec/versioning.clj`, update the versions vector to include the newest version. This will automatically change the latest version.

Also update the value for `collection-umm-version` in `cmr.common-app.config`.

### 4. Update migrations

Update the `src/cmr/umm_spec/migration/version/<concept>.clj` file. Add new functions for going from the last version to the new version and for going backwards from the new version to the last version.

### 5. Update Tests

Add tests for the new migrations in `test/cmr/umm_spec/test/migration/version/`

Create or update any other tests needed and update the collection in `src/cmr/umm_spec/test/expected_conversion.clj` to make sure the collection tests your changes.

Update any tests that use umm-spec.

### 6. Add the schema to the EMFD project

Update the EMFD project with the new directory. If you are unable to see the repository, a member of the CMR team can make the appropriate changes for you.

Create a pull request that includes the systems engineering team.

### Rules for Implementing Mappings

#### When to Use Default Values

The following table defines when we should use default values. It depends on whether the field is required or not in UMM and the XML schema and whether we're coming from XML or going to XML.

| Required in UMM | Required in XML schema | When Parsing XML | When Generating XML |
|-----------------|------------------------|------------------|---------------------|
| required        | required               | -                | -                   |
| required        | not required           | use default      | -                   |
| not required    | required               | -                | use default         |
| not required    | not required           | -                | -                   |

* We never remove default values that were added. Originally we did this but the functions have been deprecated.
* Whenever possible use a central function defined in `cmr.umm-spec.util` to decide when to apply a default value.
  * This is to allow the future possibility of making defaults optional so we can start rejecting formats that are missing some fields.

## License

Copyright © 2021 NASA
