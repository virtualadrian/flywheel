YConf
===
Simple, elegant configuration.

# About
YConf is a mapping layer for a one-way conversion of structured documents (XML, JSON, YAML, etc.) into object graphs, specifically optimised for configuration scenarios. It is not a general-purpose object serialisation framework; instead, it's like an ORM for configuration artefacts.

YConf currently supports YAML using the [SnakeYAML](https://bitbucket.org/asomov/snakeyaml) parser. Other document formats are on their way.

## Why not use _&lt;insert your favourite parser here&gt;_
Parsers such as SnakeYAML, Jackson, Gson, Genson, XStream _et al._ already support bidirectional object serialisation. And they also support custom (de)serialisers. So why the middleman?

YConf was designed to provide a parser-agnostic object mapper. With YConf you can switch from one document format to another, and your application is none the wiser. And importantly, your hand-crafted mapping code will continue to work regardless of the underlying library.

# Getting Started
## Getting YConf
Gradle builds are hosted on JCenter. Just add the following snippet to your build file (replacing the version number in the snippet with the version shown on the Download badge at the top of this README).

For Maven:

```xml
<dependency>
  <groupId>com.obsidiandynamics.yconf</groupId>
  <artifactId>yconf-core</artifactId>
  <version>0.1.0</version>
  <type>pom</type>
</dependency>
```

For Gradle:

```groovy
compile 'com.obsidiandynamics.yconf:yconf-core:0.1.0'
```


## Field injection
Assume the following YAML file:
```yaml
aString: hello
aNumber: 3.14
anObject:
  aBool: true
  anArray:
  - a
  - b
  - c
```

And the following Java classes:
```java
@Y
public class Top {
  @Y
  public static class Inner {
    @YInject
    boolean aBool;
    
    @YInject
    String[] anArray;
  }
  
  @YInject
  String aString = "some default";
  
  @YInject
  double aNumber;
  
  @YInject
  Inner inner;
}
```

All it takes is the following to map from the document to the object model, storing the result in a variable named `top`:
```java
final Top top = new MappingContext().fromStream(new FileInputStream("sample-basic.yaml"), Top.class);
```

The `aString` field in our example provides a default value. So if the document omits a value for `aString`, the default assignment will remain. This is really convenient when your configuration has sensible defaults. Beware of one gotcha: if the document provides a value, but that value is `null`, this is treated as the absence of a value. So if `null` happens to be a valid value in your scenario, it would also have to be the default value.

For the above example to work we've had to do a few things:

* Annotate the mapped classes with `@Y`;
* Annotate the mapped fields with `@YInject`; and
* Ensure that the classes have a public no-arg constructor.

The `@YInject` annotation has two optional fields:

* `name` - The name of the attribute in the document that will be mapped to the annotated field or parameter. If omitted, the name will be inferred from the annotated field. If the annotation is applied to a [constructor parameter](#user-content-constructor-injection), the name must be set explicitly.
* `type` - The `Class` type of the mapped object. If omitted, the type will be inferred from the annotated field or parameter.

## Constructor injection
Suppose you can't annotate fields and/or provide a no-arg constructor. Perhaps you are inheriting from a base class over which you have no control. The following is an alternative that uses constructor injection:
```java
@Y
public class Top {
  @Y
  public static class Inner {
    @YInject
    final boolean aBool;
    
    @YInject
    final String[] anArray;

    Inner(@YInject(name="aBool") boolean aBool, 
          @YInject(name="anArray") String[] anArray) {
      this.aBool = aBool;
      this.anArray = anArray;
    }
  }
  
  @YInject
  final String aString;
  
  @YInject
  final double aNumber;
  
  @YInject
  final Inner inner;

  Top(@YInject(name="aString") String aString,
      @YInject(name="aNumber") double aNumber, 
      @YInject(name="inner") Inner inner) {
    this.aString = aString;
    this.aNumber = aNumber;
    this.inner = inner;
  }
}
```

**Note:** When using constructor injection, the `name` attribute of `@YInject` is mandatory, as parameter names (unlike fields) cannot be inferred at runtime.

Constructor injection does not mandate a public no-arg constructor. In fact, it doesn't even require that your constructor is public. It does, however, require that the injected constructor is fully specified in terms of `@YInject` annotations. That is, each of the parameters must be annotated, or the constructor will not be used. At this stage, no behaviour is prescribed for partially annotated constructors or multiple constructors with `@YInject` annotations. This _may_ be supported in future versions.

## Hybrid injection
This is basically constructor injection, topped off with field injection - for any annotated fields that weren't set by the constructor. The latter takes place automatically, immediately after object instantiation.

# Custom Mappings
The earlier examples assume that the configuration corresponds, more or less, to the resulting object graph. It's also assumed that you have some control over the underlying classes, at least to add the appropriate annotations. Sometimes this isn't the case.

## Type mapper 101
We need to dissect some of the underlying mechanisms before we go any further. At the heart of YConf there are three main classes:

* `MappingContext` - Holds contextual data about the current mapping session, as well as settings - a registry of type mappers and DOM transforms. When you need to change YConf's behaviour, this is the class you use.
* `YObject` - A wrapper around a section of the underlying document object model (DOM) which, in turn, is the raw output of the parser. If you can visualise the entire DOM as a tree that will be mapped to the root of your resulting object graph, a `YObject` will house a subtree that corresponds to the current point in the graph where the mapper is currently operating.
* `TypeMapper` - An interface specifying how a `YObject` is mapped to an output object. This is YConf's main extension point - allowing you to specify custom mapping behaviour.

## Built-in mappers
### `RuntimeMapper`
This mapper is by default applied to everything of type `Object`, as well as to any types that are not explicitly added to the type mapper registry. In other words, if you are trying to map to an output of an unknown type, a `RuntimeMapper` is what gets used. So when wouldn't you know the target type?

One word: _polymorphism_. If the target or parameter is a subclass (or sub-interface) of the _concrete_ object, then the target type is virtually useless to the mapper. What it needs is the concrete type, and this can only come from the configuration document. The preferred way to specify the concrete type is to state its fully-qualified class name in a special `type` attribute, as illustrated in the example below.

```yaml
animals:
- type: com.acme.Dog
  name: Dingo
  breed: Labrador
- type: com.acme.Bird
  name: Olly
  wingspan: 13.47
```

The `animals` field can be an `Object[]` or an `Animal[]` (assuming `Dog` and `Bird` extend `Animal`). It really doesn't matter, as YConf will always consult the `type` attribute when no mapper is defined for the target (base) type. If, for some reason, you can't use the name `type` in your configuration (perhaps `type` is already taken to mean something else), the name attribute can be overridden as follows:

```java
new MappingContext()
.withMapper(Object.class, new RuntimeMapper().withTypeAttribute("anotherAttribute"))
.fromStream(...);
```

### `ReflectiveMapper`
This mapper was used in our initial examples, to reflectively populate with fields and parameters annotated with `@YInject`. It is also the default mapper used where an class is annotated with `@Y`, where no explicit `TypeMapper` class is specified.

### `CoercingMapper`
Coercing is the process of 'forcing' one type to another (not to be confused with casting), and is normally used with scalar values. A `CoercingMapper` performs an optional conversion by first comparing the type of the original value in the DOM with the target type, passing the value unchanged if the target type is assignable from the original. Otherwise, if the types are incompatible, coercion will occur by first reducing the original to a `String` (by calling its `toString()` method) and then invoking a supplied converter `Function`, taking in a `String` value and outputting a subclass of the target type. (`null` objects are always passed through uncoerced.)

Coercion is typically used where the original type is somewhat similar to the target type, but cannot be converted through a conventional cast or a(n) (un)boxing operation. For example, a string literal containing a sequence of digits appears to be a number, but isn't. In this case, coercion will run the original string through `Long::parseLong` (or another parser, as appropriate) to get the desired outcome.

Type boxing is another area where YConf uses coercion. For example, an object's properties are typically represented using a `Map<String, Object>` in the DOM. For number types, document parsers typically output the wider of the possible forms (e.g. `long` in place of `int`, `double` over `float`, etc.). Because values in a map must be of a reference type, a narrowing type cast cannot be used if the target (primitive or reference) type is narrower than the original reference type.

Finally, we can use coercion to translate strings to a more complex type. For example, a string containing a fully qualified class name can be coerced to a `Class` type. You can easily add your own coercions, by supplying a lambda that takes in a `String` and outputs the target type. The example below demonstrates this technique using the `URL` class (supplying the `URL(String)` constructor).

```java
new MappingContext().withMapper(URL.class, new CoercingMapper(URL.class, URL::new))...
```