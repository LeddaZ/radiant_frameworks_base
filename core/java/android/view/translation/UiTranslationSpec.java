/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view.translation;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.widget.TextView;

import com.android.internal.util.DataClass;

/**
 * Specifications for configuring UI translation.
 *
 * @hide
 */
@DataClass(
        genBuilder = true, genEqualsHashCode = true, genHiddenConstDefs = true, genToString = true)
@DataClass.Suppress("isShouldPadContentForCompat")
@SystemApi
public final class UiTranslationSpec implements Parcelable {

    /**
     * Whether the original content of the view should be made to appear as if it is the same size
     * as the translated content. Defaults to {@code false}.
     * <p>
     * For {@link TextView}, the system does not directly modify the original text, rather
     * changes the displayed content using a {@link android.text.method.TransformationMethod}. This
     * can cause issues in apps that do not account for length-changing TransformationMethods. For
     * example, an app using DynamicLayout may use the calculated line-offsets to operate on the
     * original text, but this can cause crashes when the layout was calculated on translated text
     * with a different length.
     * <p>
     * If this is {@code true}, for a TextView the default implementation appends spaces to the
     * result of {@link TextView#getText()} to make the length the same as the translated text.
     * <p>
     * This only affects apps with target SDK R or lower.
     */
    private boolean mShouldPadContentForCompat = false;

    /**
     * Whether the original content of the view should be made to appear as if it is the same size
     * as the translated content.
     * <p>
     * For {@link TextView}, the system does not directly modify the original text, rather
     * changes the displayed content using a {@link android.text.method.TransformationMethod}. This
     * can cause issues in apps that do not account for length-changing TransformationMethods. For
     * example, an app using DynamicLayout may use the calculated line-offsets to operate on the
     * original text, but this can cause crashes when the layout was calculated on translated text
     * with a different length.
     * <p>
     * If this is {@code true}, for a TextView the default implementation appends spaces to the
     * result of {@link TextView#getText()} to make the length the same as the translated text.
     * <p>
     * This only affects apps with target SDK R or lower.
     */
    public boolean shouldPadContentForCompat() {
        return mShouldPadContentForCompat;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/view/translation/UiTranslationSpec.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    /* package-private */ UiTranslationSpec(
            boolean shouldPadContentForCompat) {
        this.mShouldPadContentForCompat = shouldPadContentForCompat;

        // onConstructed(); // You can define this method to get a callback
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "UiTranslationSpec { " +
                "shouldPadContentForCompat = " + mShouldPadContentForCompat +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@android.annotation.Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(UiTranslationSpec other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        UiTranslationSpec that = (UiTranslationSpec) o;
        //noinspection PointlessBooleanExpression
        return true
                && mShouldPadContentForCompat == that.mShouldPadContentForCompat;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + Boolean.hashCode(mShouldPadContentForCompat);
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mShouldPadContentForCompat) flg |= 0x1;
        dest.writeByte(flg);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ UiTranslationSpec(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        boolean shouldPadContentForCompat = (flg & 0x1) != 0;

        this.mShouldPadContentForCompat = shouldPadContentForCompat;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<UiTranslationSpec> CREATOR
            = new Parcelable.Creator<UiTranslationSpec>() {
        @Override
        public UiTranslationSpec[] newArray(int size) {
            return new UiTranslationSpec[size];
        }

        @Override
        public UiTranslationSpec createFromParcel(@NonNull Parcel in) {
            return new UiTranslationSpec(in);
        }
    };

    /**
     * A builder for {@link UiTranslationSpec}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder {

        private boolean mShouldPadContentForCompat;

        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        /**
         * Whether the original content of the view should be made to appear as if it is the same size
         * as the translated content. Defaults to {@code false}.
         * <p>
         * For {@link TextView}, the system does not directly modify the original text, rather
         * changes the displayed content using a {@link android.text.method.TransformationMethod}. This
         * can cause issues in apps that do not account for length-changing TransformationMethods. For
         * example, an app using DynamicLayout may use the calculated line-offsets to operate on the
         * original text, but this can cause crashes when the layout was calculated on translated text
         * with a different length.
         * <p>
         * If this is {@code true}, for a TextView the default implementation appends spaces to the
         * result of {@link TextView#getText()} to make the length the same as the translated text.
         * <p>
         * This only affects apps with target SDK R or lower.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setShouldPadContentForCompat(boolean value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mShouldPadContentForCompat = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull UiTranslationSpec build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mShouldPadContentForCompat = false;
            }
            UiTranslationSpec o = new UiTranslationSpec(
                    mShouldPadContentForCompat);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x2) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1620790033058L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/view/translation/UiTranslationSpec.java",
            inputSignatures = "private  boolean mShouldPadContentForCompat\npublic  boolean shouldPadContentForCompat()\nclass UiTranslationSpec extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genBuilder=true, genEqualsHashCode=true, genHiddenConstDefs=true, genToString=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
