import { redirect } from "next/navigation";

/** Root sends users into the app; the proxy bounces unauthenticated ones to /login. */
export default function Home() {
  redirect("/pipeline");
}
