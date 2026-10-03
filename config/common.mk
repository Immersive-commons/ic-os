# IC OS vendor layer. A device or product makefile inherits this:
#   $(call inherit-product, vendor/ic/config/common.mk)

PRODUCT_PACKAGES += \
    ICSensorBus

# The sensor bus is the only app allowed to hold raw sensors in the background,
# so it must start with the system, not wait for a user to open something.
PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.ic.os.version=0.1
