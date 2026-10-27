#!/usr/bin/env python3
"""vds_query.py — перевірка протоколу OWON VDS C2 з ПК (Linux, pyusb).

Використання:
    python3 vds_query.py                # знайти прилад, запитати тип
    python3 vds_query.py 82 4 1         # записати регістр (адреса, len, значення)
    python3 vds_query.py frame          # прочитати кадр осцилограми (CH1+CH2)

Потрібно: pip install pyusb
"""
import struct
import sys

import usb.core  # noqa: E402

VID, PID = 0x5345, 0x1234
PACKET = 5211
HDR, TRG, DM = 11, 100, 5100


def pack_cmd(address: int, n: int, value: int) -> bytes:
    return struct.pack("<IB", address, n) + value.to_bytes(n, "little")


def main() -> int:
    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        print("Прилад не знайдено (VID 0x5345 / PID 0x1234)")
        return 1
    if dev.is_kernel_driver_active(0):
        dev.detach_kernel_driver(0)
    dev.set_configuration()
    cfg = dev.get_active_configuration()
    intf = cfg[(0, 0)]
    ep_out = None
    ep_in = None
    for ep in intf:
        if ep.bmAttributes & 0x03 != 0x02:  # bulk
            continue
        if ep.bEndpointAddress & 0x80:
            ep_in = ep
        else:
            ep_out = ep
    if not ep_in or not ep_out:
        print("Bulk-ендпоінти не знайдено")
        return 1
    print(f"Знайдено: {hex(dev.idVendor)}:{hex(dev.idProduct)}, "
          f"OUT 0x{ep_out.bEndpointAddress:02x} / IN 0x{ep_in.bEndpointAddress:02x}")

    def cmd(address, n, value):
        ep_out.write(pack_cmd(address, n, value), 1000)
        return struct.unpack("<I", ep_in.read(5, 1000)[1:5])[0]

    machine = cmd(16385, 1, 86)
    print(f"MACHINE_TYPE → {machine}")
    if machine <= 0:
        print("Прилад не відповів коректно")
        return 1

    if len(sys.argv) >= 4:
        add, n, val = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
        print(f"WRITE {add} (0x{add:x}), len={n}, value={val} → {cmd(add, n, val)}")
    elif len(sys.argv) > 1 and sys.argv[1] == "frame":
        chlflag = 0x0505
        ep_out.write(pack_cmd(4096, 2, chlflag), 1000)
        for pool in range(2):
            data = bytes(ep_in.read(PACKET, 3000))
            if data[0] == 0x45:
                print("EBUSY — тригер не спрацював")
                return 0
            dm = data[HDR + TRG:]
            chl = dm[0]
            time_sum, peroid = struct.unpack_from("<II", dm, 1)
            samples = dm[11:11 + 2500]
            print(f"CH{chl + 1}: time_sum={time_sum} peroid={peroid} "
                  f"семплів={len(samples)} перші={list(samples[:8])}")
    else:
        print("Готово. Аргументи: [адреса len значення] | frame")
    return 0


if __name__ == "__main__":
    sys.exit(main())
