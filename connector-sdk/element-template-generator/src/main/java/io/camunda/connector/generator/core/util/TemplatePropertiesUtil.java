/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.camunda.connector.generator.core.util;

import static io.camunda.connector.generator.core.util.ReflectionUtil.getAllFields;
import static io.camunda.connector.generator.core.util.ReflectionUtil.getRequiredAnnotation;

import io.camunda.connector.generator.annotation.TemplateProperty;
import io.camunda.connector.generator.annotation.TemplateProperty.PropertyType;
import io.camunda.connector.generator.annotation.TemplateSubType;
import io.camunda.connector.generator.dsl.BooleanProperty;
import io.camunda.connector.generator.dsl.DropdownProperty;
import io.camunda.connector.generator.dsl.DropdownProperty.DropdownChoice;
import io.camunda.connector.generator.dsl.HiddenProperty;
import io.camunda.connector.generator.dsl.Property;
import io.camunda.connector.generator.dsl.PropertyBinding;
import io.camunda.connector.generator.dsl.PropertyBuilder;
import io.camunda.connector.generator.dsl.PropertyCondition;
import io.camunda.connector.generator.dsl.PropertyCondition.Equals;
import io.camunda.connector.generator.dsl.PropertyCondition.OneOf;
import io.camunda.connector.generator.dsl.PropertyGroup;
import io.camunda.connector.generator.dsl.StringProperty;
import io.camunda.connector.generator.dsl.TextProperty;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Utility class for transforming data classes into {@link PropertyBuilder} instances. */
public class TemplatePropertiesUtil {

  /**
   * Analyze the type and return a list of {@link PropertyBuilder} instances.
   *
   * <p>Capabilities:
   *
   * <ul>
   *   <li>primitive types and Strings are mapped to template properties according to their type
   *   <li>nested types are handled recursively
   *   <li>{@link TemplateProperty} annotations are taken into account
   *   <li>Sealed hierarchies are supported by adding an extra Dropdown discriminator property.
   *       {@link TemplateSubType} annotations are used to configure the sealed hierarchies.
   * </ul>
   *
   * <p>Note: {@link PropertyBuilder#binding(PropertyBinding)} is not set by this method. The caller
   * is responsible for setting the binding according to the connector type (inbound or outbound).
   *
   * @param type the type to analyze
   * @return a list of {@link PropertyBuilder} instances
   */
  public static List<PropertyBuilder> extractTemplatePropertiesFromType(Class<?> type) {
    if (type.isSealed()) {
      return handleSealedType(type);
    }

    var fields = getAllFields(type);
    var properties = new ArrayList<PropertyBuilder>();

    for (Field field : fields) {
      if (isContainerType(field.getType())) {
        // analyze recursively
        var nestedProperties =
            extractTemplatePropertiesFromType(field.getType()).stream()
                .map(builder -> addPathPrefix(builder, field.getName()))
                .toList();
        properties.addAll(nestedProperties);
      } else {
        properties.add(buildProperty(field));
      }
    }
    return properties;
  }

  /**
   * Create property groups from a list of properties based on {@link TemplateProperty#group()}.
   *
   * <p>Properties without a group are ignored.
   *
   * @param properties the properties to group
   * @return a list of {@link PropertyGroup} instances
   */
  public static List<PropertyGroup> groupProperties(List<Property> properties) {
    return properties.stream()
        .filter(property -> property.getGroup() != null)
        .collect(Collectors.groupingBy(Property::getGroup))
        .entrySet()
        .stream()
        .map(
            entry ->
                PropertyGroup.builder()
                    .id(entry.getKey())
                    .label(transformIdIntoLabel(entry.getKey()))
                    .properties(entry.getValue())
                    .build())
        .toList();
  }

  private static PropertyBuilder buildProperty(Field field) {
    var annotation = field.getAnnotation(TemplateProperty.class);
    PropertyBuilder propertyBuilder =
        createPropertyBuilder(field, annotation)
            .id(field.getName())
            .label(transformIdIntoLabel(field.getName()));
    return TemplatePropertyAnnotationUtil.applyAnnotation(propertyBuilder, annotation);
  }

  private static PropertyBuilder addPathPrefix(PropertyBuilder builder, String path) {
    builder.id(path + "." + builder.getId());
    var built = builder.build();
    if (built.getCondition() != null) {
      if (built.getCondition() instanceof OneOf oneOfCondition) {
        builder.condition(
            new OneOf(path + "." + oneOfCondition.property(), oneOfCondition.oneOf()));
      } else if (built.getCondition() instanceof Equals equalsCondition) {
        builder.condition(
            new Equals(path + "." + equalsCondition.property(), equalsCondition.equals()));
      }
    }
    return builder;
  }

  private static PropertyBuilder createPropertyBuilder(Field field, TemplateProperty annotation) {
    PropertyType type = null;
    String[] dropdownChoices = null;

    if (annotation != null) {
      type = annotation.type();
      dropdownChoices = annotation.choices();
    }

    if (type == null || type == PropertyType.Unknown) {
      if (field.getType() == Boolean.class) {
        type = PropertyType.Boolean;
      } else if (field.getType().isEnum()) {
        type = PropertyType.Dropdown;
        if (dropdownChoices == null) {
          dropdownChoices =
              Arrays.stream(field.getType().getEnumConstants())
                  .map(Object::toString)
                  .toArray(String[]::new);
        }
      } else {
        type = PropertyType.String;
      }
    }
    return switch (type) {
      case Boolean -> BooleanProperty.builder();
      case Dropdown -> DropdownProperty.builder()
          .choices(
              Arrays.stream(dropdownChoices)
                  .map(choice -> new DropdownChoice(transformIdIntoLabel(choice), choice))
                  .toList());
      case Hidden -> HiddenProperty.builder();
      case String -> StringProperty.builder();
      case Text -> TextProperty.builder();
      case Unknown -> throw new IllegalStateException("Unknown property type");
    };
  }

  private static List<PropertyBuilder> handleSealedType(Class<?> type) {
    var subTypes = type.getPermittedSubclasses();
    var properties = new ArrayList<PropertyBuilder>();

    String discriminatorProperty = null;

    Set<String> values = new HashSet<>();

    for (Class<?> subType : subTypes) {
      var subTypeAnnotation = getRequiredAnnotation(subType, TemplateSubType.class);

      values.addAll(Arrays.asList(subTypeAnnotation.oneOf()));
      values.add(subTypeAnnotation.equals()); // add both, handle nulls later

      if (discriminatorProperty == null) {
        discriminatorProperty = subTypeAnnotation.discriminatorProperty();
      } else {
        if (!discriminatorProperty.equals(subTypeAnnotation.discriminatorProperty())) {
          throw new IllegalStateException(
              "Subtype "
                  + subType
                  + " of sealed type "
                  + type
                  + " has different conditional property than other subtypes");
        }
      }

      var currentSubTypeProperties =
          extractTemplatePropertiesFromType(subType).stream()
              .map(
                  property ->
                      property.condition(getConditionFromSubTypeAnnotation(subTypeAnnotation)))
              .toList();

      properties.addAll(currentSubTypeProperties);
    }

    if (discriminatorProperty == null) {
      throw new IllegalStateException("Sealed type " + type + " has no subtypes");
    }

    var discriminator =
        DropdownProperty.builder()
            .choices(
                values.stream()
                    .filter(Objects::nonNull)
                    .map(value -> new DropdownChoice(transformIdIntoLabel(value), value))
                    .collect(Collectors.toList()))
            .id(discriminatorProperty)
            .optional(false)
            .group("settings")
            .label(transformIdIntoLabel(discriminatorProperty));

    var result = new ArrayList<>(List.of(discriminator));
    result.addAll(properties);
    return result;
  }

  private static PropertyCondition getConditionFromSubTypeAnnotation(
      TemplateSubType subTypeAnnotation) {

    if (!subTypeAnnotation.equals().isBlank()) {
      return new PropertyCondition.Equals(
          subTypeAnnotation.discriminatorProperty(), subTypeAnnotation.equals());
    } else if (subTypeAnnotation.oneOf().length > 0) {
      return new PropertyCondition.OneOf(
          subTypeAnnotation.discriminatorProperty(), Arrays.asList(subTypeAnnotation.oneOf()));
    }
    throw new IllegalStateException(
        "@TemplateSubType annotation must have either 'equals' or 'oneOf' attribute");
  }

  public static String transformIdIntoLabel(String id) {
    // A simple attempt to transform camelCase into a normal sentence (first letter capitalized,
    // spaces)
    var label = new StringBuilder();
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      if (i == 0) {
        label.append(Character.toUpperCase(c));
      } else if (Character.isUpperCase(c)
          || (Character.isDigit(c) && !Character.isDigit(id.charAt(i - 1)))) {
        label.append(" ").append(Character.toLowerCase(c));
      } else {
        label.append(c);
      }
    }
    return label.toString();
  }

  private static boolean isContainerType(Class<?> type) {
    // true if object with fields, false if primitive or collection or map or array
    if (type.isPrimitive() || type.isAssignableFrom(String.class)) {
      return false;
    }
    if (type.isArray()
        || Collection.class.isAssignableFrom(type)
        || Map.class.isAssignableFrom(type)) {
      return false;
    }
    return true;
  }
}